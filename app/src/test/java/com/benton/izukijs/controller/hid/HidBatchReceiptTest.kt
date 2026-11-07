package com.benton.izukijs.controller.hid

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class HidBatchReceiptTest {
    private fun frame() = HidProtocol.tap(100, 200, 50)

    @Test fun trackedTapRejectsReservedRepeatControlsWithoutChangingLegacyEncoding() {
        assertNull(HidController.trackedTapRejection(100, 200, 65533))
        for (duration in listOf(65534, 65535)) {
            assertEquals("reserved_repeat_control_duration", HidController.trackedTapRejection(100, 200, duration))
            val legacy = HidProtocol.tap(100, 200, duration)
            assertEquals(duration, (legacy[5].toInt() and 255) or ((legacy[6].toInt() and 255) shl 8))
        }
    }

    @Test fun admissionAndPartialProgressAreNotExecutionConfirmation() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val receipt = link.submit(epoch, listOf(frame(), frame()), 0)
        assertEquals(HidBatchStatus.ACCEPTED, receipt.status)
        assertEquals(0, receipt.transportStartedFrames)
        assertFalse(receipt.executionConfirmed)
        val first = link.take(0)!!
        assertEquals(1, link.receipt(receipt.id)!!.transportStartedFrames)
        link.complete(epoch, first.id, true)
        val partial = link.receipt(receipt.id)!!
        assertEquals(1, partial.transportCompletedFrames)
        assertFalse(partial.terminal)
        assertTrue(partial.executionUncertain)
        val second = link.take(1)!!
        link.complete(epoch, second.id, true)
        val completed = link.receipt(receipt.id)!!
        assertEquals(HidBatchStatus.TRANSPORT_COMPLETE, completed.status)
        assertEquals(2, completed.transportCompletedFrames)
        assertEquals(2, completed.transportStartedFrames)
        assertFalse(completed.executionConfirmed)
    }

    @Test fun failedSuffixPreservesTransportCompletedPrefix() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val receipt = link.submit(epoch, List(3) { frame() }, 0)
        val first = link.take(0)!!
        link.complete(epoch, first.id, true)
        val second = link.take(1)!!
        link.complete(epoch, second.id, false, "write_status:133")
        val failed = link.receipt(receipt.id)!!
        assertEquals(HidBatchStatus.FAILED, failed.status)
        assertEquals(1, failed.transportCompletedFrames)
        assertEquals(2, failed.transportStartedFrames)
        assertEquals("write_status:133", failed.reason)
        assertTrue(failed.executionUncertain)
        assertNull(link.take(2))
        link.reset(3)
        link.fail("another_fault")
        assertEquals(failed, link.receipt(receipt.id))
    }

    @Test fun resetCancelsOutstandingBatchButDoesNotRollbackPrefix() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val receipt = link.submit(epoch, listOf(frame(), frame()), 0)
        val write = link.take(0)!!
        link.complete(epoch, write.id, true)
        link.reset(1)
        val cancelled = link.receipt(receipt.id)!!
        assertEquals(HidBatchStatus.CANCELLED, cancelled.status)
        assertEquals(epoch, cancelled.session)
        assertEquals(1, cancelled.transportCompletedFrames)
        assertTrue(cancelled.executionUncertain)
        assertEquals("session_reset", cancelled.reason)
    }

    @Test fun lostCallbackExpiresInflightAndCancelsOtherBatchWithoutReplay() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val running = link.submit(epoch, listOf(frame()), 0)
        val queued = link.submit(epoch, listOf(frame()), 0)
        val write = link.take(0)!!
        link.check(2000)
        val expired = link.receipt(running.id)!!
        assertEquals(HidBatchStatus.EXPIRED, expired.status)
        assertEquals("write_timeout", expired.reason)
        assertEquals(0, expired.transportCompletedFrames)
        assertTrue(expired.executionUncertain)
        val cancelled = link.receipt(queued.id)!!
        assertEquals(HidBatchStatus.CANCELLED, cancelled.status)
        assertEquals("link_aborted:write_timeout", cancelled.reason)
        assertEquals(0, cancelled.transportStartedFrames)
        assertFalse(cancelled.executionUncertain)
        assertFalse(link.complete(epoch, write.id, true))
        assertNull(link.take(2001))
    }

    @Test fun queueExpiryNeverClaimsTheFrameWasTransported() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val receipt = link.submit(epoch, listOf(frame()), 0)
        assertNull(link.take(2000))
        val expired = link.receipt(receipt.id)!!
        assertEquals(HidBatchStatus.EXPIRED, expired.status)
        assertEquals("queued_command_expired", expired.reason)
        assertEquals(0, expired.transportStartedFrames)
        assertEquals(0, expired.transportCompletedFrames)
        assertFalse(expired.executionConfirmed)
    }

    @Test fun queuedSuffixExpiryPreservesEarlierCompletedFrame() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val receipt = link.submit(epoch, listOf(frame(), frame()), 0)
        val first = link.take(0)!!
        link.complete(epoch, first.id, true)
        assertNull(link.take(2000))
        val expired = link.receipt(receipt.id)!!
        assertEquals(HidBatchStatus.EXPIRED, expired.status)
        assertEquals(1, expired.transportCompletedFrames)
        assertTrue(expired.executionUncertain)
    }

    @Test fun oldSessionCallbacksCannotCompleteNewReceipt() {
        val link = HidLinkState()
        val old = link.reset(0)
        val oldReceipt = link.submit(old, listOf(frame()), 0)
        val oldWrite = link.take(0)!!
        val fresh = link.reset(1)
        val freshReceipt = link.submit(fresh, listOf(frame()), 1)
        val freshWrite = link.take(1)!!
        assertFalse(link.complete(old, oldWrite.id, true))
        assertEquals(HidBatchStatus.ACCEPTED, link.receipt(freshReceipt.id)!!.status)
        assertEquals(0, link.receipt(freshReceipt.id)!!.transportCompletedFrames)
        assertTrue(link.complete(fresh, freshWrite.id, true))
        assertEquals(HidBatchStatus.CANCELLED, link.receipt(oldReceipt.id)!!.status)
    }

    @Test fun capacityRejectsWholeBatchAndKeepsOriginalPendingReceipt() {
        val link = HidLinkState(capacity = 2)
        val epoch = link.reset(0)
        val accepted = link.submit(epoch, listOf(frame()), 0)
        val rejected = link.submit(epoch, listOf(frame(), frame()), 0)
        assertEquals(HidBatchStatus.REJECTED, rejected.status)
        assertEquals("queue_capacity", rejected.reason)
        assertEquals(2, rejected.frameCount)
        assertFalse(rejected.accepted)
        val write = link.take(0)!!
        assertEquals(accepted.id, write.batchId)
        link.complete(epoch, write.id, true)
        assertNull(link.take(1))
    }

    @Test fun terminalHistoryIsBoundedWhilePendingSurvivesRejectionFlood() {
        val link = HidLinkState(capacity = 1, historyCapacity = 2)
        val epoch = link.reset(0)
        val pending = link.submit(epoch, listOf(frame()), 0)
        val rejected = List(100) { link.submit(epoch, listOf(frame()), 0) }
        assertNotNull(link.receipt(pending.id))
        assertNull(link.receipt(rejected.first().id))
        assertNotNull(link.receipt(rejected[98].id))
        assertNotNull(link.receipt(rejected[99].id))
        val write = link.take(0)!!
        link.complete(epoch, write.id, true)
        // Old admission order must not evict a newly completed pending receipt.
        assertNotNull(link.receipt(pending.id))
    }

    @Test fun idsAreIndependentAcrossClientsAndResets() {
        val a = HidLinkState()
        val b = HidLinkState()
        val first = a.submit(a.reset(0), listOf(frame()), 0)
        val second = b.submit(b.reset(0), listOf(frame()), 0)
        val third = a.submit(a.reset(1), listOf(frame()), 1)
        assertEquals(3, setOf(first.id, second.id, third.id).size)
        assertNull(b.receipt(first.id))
    }

    @Test fun concurrentSubmissionsHaveDistinctIdsAndAtomicAdmission() {
        val link = HidLinkState(capacity = 4)
        val epoch = link.reset(0)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val receipts = (0 until 20).map { pool.submit<HidBatchReceipt> {
                link.submit(epoch, listOf(frame(), frame()), 0)
            } }.map { it.get() }
            assertEquals(20, receipts.map { it.id }.toSet().size)
            assertEquals(2, receipts.count { it.accepted })
            repeat(4) { val write = link.take(0)!!; link.complete(epoch, write.id, true) }
            assertNull(link.take(1))
            receipts.filter { it.accepted }.forEach {
                assertEquals(2, link.receipt(it.id)!!.transportCompletedFrames)
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun faultCancelsEveryOutstandingBatchButLeavesCompletedHistoryImmutable() {
        val link = HidLinkState()
        val epoch = link.reset(0)
        val completed = link.submit(epoch, listOf(frame()), 0)
        val write = link.take(0)!!
        link.complete(epoch, write.id, true)
        val pending = link.submit(epoch, listOf(frame()), 1)
        link.fail("disconnected")
        assertEquals(HidBatchStatus.TRANSPORT_COMPLETE, link.receipt(completed.id)!!.status)
        assertEquals(HidBatchStatus.FAILED, link.receipt(pending.id)!!.status)
        assertEquals("disconnected", link.receipt(pending.id)!!.reason)
        assertNull(link.take(2))
    }

    @Test fun legacyTrafficDoesNotAllocateOrEvictTrackedHistory() {
        val link = HidLinkState(historyCapacity = 1)
        val epoch = link.reset(0)
        val tracked = link.submit(epoch, listOf(frame()), 0)
        var write = link.take(0)!!
        link.complete(epoch, write.id, true)
        repeat(1000) {
            assertTrue(link.enqueue(epoch, listOf(frame()), 0))
            write = link.take(0)!!
            assertNull(write.batchId)
            link.complete(epoch, write.id, true)
        }
        assertEquals(HidBatchStatus.TRANSPORT_COMPLETE, link.receipt(tracked.id)!!.status)
    }

    @Test fun priorityProbeDoesNotConsumeBusinessCapacityOrReorderBusinessFrames() {
        val link = HidLinkState(writeTimeoutMs = 20000, queueTimeoutMs = 20000, capacity = 2)
        val epoch = link.reset(0)
        val a = link.submit(epoch, listOf(frame()), 0)
        val b = link.submit(epoch, listOf(frame()), 0)
        val first = link.take(0)!!
        assertFalse(link.shouldProbe(3000, false))
        assertTrue(link.shouldProbe(3000, true))
        assertTrue(link.enqueueProbe(epoch, 3000, true))
        assertFalse(link.enqueueProbe(epoch, 3000, true))
        assertEquals(HidBatchStatus.REJECTED, link.submit(epoch, listOf(frame()), 3000).status)
        assertEquals(a.id, first.batchId)
        link.complete(epoch, first.id, true)
        val probe = link.take(3001)!!
        assertEquals(HidProtocol.CMD_PING, HidProtocol.kind(probe.payload))
        assertEquals(true, probe.realtimeProbe)
        val c = link.submit(epoch, listOf(frame()), 3001)
        assertTrue(c.accepted)
        link.probeSent(3001, true)
        link.complete(epoch, probe.id, true)
        link.pong(3002)
        val second = link.take(3002)!!
        assertEquals(b.id, second.batchId)
        link.complete(epoch, second.id, true)
        val third = link.take(3002)!!
        assertEquals(c.id, third.batchId)
    }

    @Test fun queuedProbeDoesNotStartPongDeadlineBeforeActualWriteStarts() {
        val link = HidLinkState(writeTimeoutMs = 100000, queueTimeoutMs = 100000)
        val epoch = link.reset(0)
        link.enqueue(epoch, listOf(frame()), 0)
        val business = link.take(0)!!
        assertTrue(link.enqueueProbe(epoch, 3000, true))
        assertNull(link.check(8001))
        link.complete(epoch, business.id, true)
        val probe = link.take(8002)!!
        link.probeSent(8002, true)
        link.complete(epoch, probe.id, true)
        assertNull(link.check(13001))
        assertEquals("heartbeat_timeout", link.check(13002))
    }

    @Test fun resetDropsQueuedPriorityProbeAndMakesOldProbeCallbackStale() {
        val link = HidLinkState()
        val old = link.reset(0)
        assertTrue(link.enqueueProbe(old, 3000, true))
        val probe = link.take(3000)!!
        val fresh = link.reset(3001)
        val receipt = link.submit(fresh, listOf(frame()), 3001)
        assertFalse(link.complete(old, probe.id, true))
        assertEquals(receipt.id, link.take(3001)!!.batchId)
        assertEquals(HidBatchStatus.ACCEPTED, link.receipt(receipt.id)!!.status)
    }
}
