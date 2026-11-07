package com.benton.izukijs.controller.hid

import java.util.ArrayDeque
import java.util.UUID

/** Transport bookkeeping, independent of Android so faults can be injected deterministically.
 * A failed/expired transmission is never replayed. GATT completion is not input execution.
 */
internal class HidLinkState(
    private val writeTimeoutMs: Long = 2_000,
    private val queueTimeoutMs: Long = 2_000,
    private val capacity: Int = 32,
    private val historyCapacity: Int = 128,
) {
    data class Write(val session: Long, val id: Long, val payload: ByteArray, val acceptedAt: Long,
                     val batchId: String? = null, val realtimeProbe: Boolean? = null)
    private val pending = ArrayDeque<Write>()
    private var priorityProbe: Write? = null
    private var serial = 0L
    private val receiptScope = UUID.randomUUID().toString()
    private var batchSerial = 0L
    private val receipts = LinkedHashMap<String, HidBatchReceipt>()
    @Volatile var session = 0L; private set
    @Volatile var inFlight: Write? = null; private set
    private var writeStartedAt = 0L
    @Volatile var busyUntil = 0L; private set
    @Volatile var fault: String? = null; private set
    private var pongDeadline = 0L
    private var nextProbeAt = 0L

    init { require(capacity > 0); require(historyCapacity > 0) }

    /** Admission is atomic for every frame. Legacy enqueue deliberately creates no history. */
    @Synchronized fun submit(epoch: Long, frames: List<ByteArray>, now: Long): HidBatchReceipt {
        val rejection = rejection(epoch, frames)
        if (rejection != null) return reject(epoch, frames.size, rejection)
        val receipt = HidBatchReceipt(nextBatchId(), session, HidBatchStatus.ACCEPTED, frames.size)
        receipts[receipt.id] = receipt
        append(frames, now, receipt.id)
        return receipt
    }

    @Synchronized fun reject(epoch: Long, frameCount: Int, reason: String): HidBatchReceipt {
        val receipt = HidBatchReceipt(nextBatchId(), epoch, HidBatchStatus.REJECTED, frameCount, reason = reason)
        receipts[receipt.id] = receipt
        pruneHistory()
        return receipt
    }

    @Synchronized fun receipt(id: String): HidBatchReceipt? = receipts[id]

    private fun nextBatchId() = "$receiptScope:${++batchSerial}"

    private fun rejection(epoch: Long, frames: List<ByteArray>): String? = when {
        epoch != session -> "stale_session"
        fault != null -> "link_fault:$fault"
        frames.isEmpty() -> "empty_batch"
        frames.any { it.isEmpty() } -> "empty_frame"
        frames.size > capacity - pending.size - (inFlight?.let { if (it.realtimeProbe == null) 1 else 0 } ?: 0) -> "queue_capacity"
        else -> null
    }

    private fun append(frames: List<ByteArray>, now: Long, batchId: String?) {
        frames.forEach { pending.addLast(Write(session, ++serial, it.copyOf(), now, batchId)) }
    }

    /** Bound terminal records separately so rejection/history traffic never evicts pending. */
    private fun pruneHistory() {
        var excess = receipts.values.count { it.terminal } - historyCapacity
        if (excess <= 0) return
        val iterator = receipts.entries.iterator()
        while (iterator.hasNext() && excess > 0) {
            if (iterator.next().value.terminal) { iterator.remove(); excess-- }
        }
    }

    private fun terminate(status: HidBatchStatus, reason: String, expiredBatch: String? = null) {
        val active = receipts.values.filter { !it.terminal }
        active.forEach { receipt ->
            val id = receipt.id
            receipts.remove(id)
            receipts[id] = receipt.copy(
                status = if (status == HidBatchStatus.EXPIRED && id != expiredBatch) HidBatchStatus.CANCELLED else status,
                reason = if (status == HidBatchStatus.EXPIRED && id != expiredBatch) "link_aborted:$reason" else reason,
            )
        }
        pruneHistory()
    }

    @Synchronized fun reset(now: Long): Long {
        terminate(HidBatchStatus.CANCELLED, "session_reset")
        session++
        pending.clear(); priorityProbe = null; inFlight = null; fault = null
        writeStartedAt = 0; busyUntil = now; pongDeadline = 0; nextProbeAt = now + 3_000
        return session
    }

    @Synchronized fun enqueue(epoch: Long, frames: List<ByteArray>, now: Long): Boolean {
        if (rejection(epoch, frames) != null) return false
        append(frames, now, null)
        return true
    }

    @Synchronized fun take(now: Long): Write? {
        if (check(now) != null || inFlight != null) return null
        val write = priorityProbe?.also { priorityProbe = null } ?: pending.pollFirst() ?: return null
        inFlight = write; writeStartedAt = now
        write.batchId?.let { id -> receipts[id]?.let { receipts[id] = it.copy(transportStartedFrames = it.transportStartedFrames + 1) } }
        busyUntil = maxOf(now, busyUntil) + HidProtocol.executionBudgetMs(write.payload)
        return write
    }

    @Synchronized fun complete(epoch: Long, id: Long, success: Boolean, failureReason: String = "write_failed"): Boolean {
        if (epoch != session || inFlight?.id != id) return false
        val write = inFlight!!
        inFlight = null
        if (!success) fail(failureReason) else write.batchId?.let { batchId ->
            receipts[batchId]?.let { receipt ->
                val count = receipt.transportCompletedFrames + 1
                if (count == receipt.frameCount) receipts.remove(batchId)
                receipts[batchId] = receipt.copy(transportCompletedFrames = count,
                    status = if (count == receipt.frameCount) HidBatchStatus.TRANSPORT_COMPLETE else HidBatchStatus.ACCEPTED)
                pruneHistory()
            }
        }
        return true
    }

    @Synchronized fun fail(reason: String) {
        if (fault == null) fault = reason
        terminate(HidBatchStatus.FAILED, reason)
        pending.clear(); priorityProbe = null; inFlight = null; pongDeadline = 0
    }

    private fun expire(reason: String, batchId: String?) {
        fault = reason
        terminate(HidBatchStatus.EXPIRED, reason, batchId)
        pending.clear(); priorityProbe = null; inFlight = null; pongDeadline = 0
    }

    @Synchronized fun check(now: Long): String? {
        if (fault != null) return fault
        when {
            inFlight != null && now - writeStartedAt >= writeTimeoutMs -> expire("write_timeout", inFlight?.batchId)
            pending.peekFirst()?.let { now - it.acceptedAt >= queueTimeoutMs } == true -> expire("queued_command_expired", pending.peekFirst()?.batchId)
            pongDeadline != 0L && now >= pongDeadline -> fail("heartbeat_timeout")
        }
        return fault
    }

    @Synchronized fun shouldProbe(now: Long, realtimePong: Boolean): Boolean =
        fault == null && pongDeadline == 0L && priorityProbe == null &&
            inFlight?.realtimeProbe == null && now >= nextProbeAt &&
            (realtimePong || (inFlight == null && pending.isEmpty() && now >= busyUntil))

    /** One reserved control slot prevents v3 heartbeat starvation under business load.
     * It does not consume business capacity or overtake the one in-flight GATT write.
     * The PONG deadline starts only after the client actually starts this write.
     */
    @Synchronized fun enqueueProbe(epoch: Long, now: Long, realtimePong: Boolean): Boolean {
        if (epoch != session || !shouldProbe(now, realtimePong)) return false
        priorityProbe = Write(session, ++serial, HidProtocol.ping(), now, realtimeProbe = realtimePong)
        return true
    }

    @Synchronized fun probeSent(now: Long, realtimePong: Boolean) {
        pongDeadline = maxOf(now, if (realtimePong) now else busyUntil) + 5_000
    }

    @Synchronized fun pong(now: Long) {
        if (pongDeadline != 0L) {
            pongDeadline = 0; nextProbeAt = now + 3_000
        }
    }
}
