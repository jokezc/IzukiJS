package com.benton.izukijs.subscription

import java.net.URL
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import java.io.InputStream

object SubscriptionSync {

    const val MIN_INTERVAL_MINUTES = 15L
    const val DEFAULT_INTERVAL_MINUTES = 24L * 60L
    const val MAX_SCRIPT_BYTES = 1024 * 1024

    enum class UpdateDecision { UNCHANGED, OVERWRITE, CONFLICT }

    /** 订阅与扫码导入都只接受带主机的 http/https 链接。 */
    fun isHttpUrl(raw: String): Boolean = runCatching {
        val parsed = URL(raw.trim())
        val scheme = parsed.protocol.lowercase()
        (scheme == "http" || scheme == "https") && !parsed.host.isNullOrBlank()
    }.getOrDefault(false)

    fun normalizeInterval(minutes: Long?): Long {
        if (minutes == null) return DEFAULT_INTERVAL_MINUTES
        if (minutes <= 0L) return 0L
        return minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
    }

    fun isDisabled(minutes: Long): Boolean = minutes <= 0L

    fun canSync(subscription: ScriptSubscription, automatic: Boolean, now: Long): Boolean =
        !automatic || (subscription.enabled && !isDisabled(subscription.intervalMinutes) &&
            subscription.nextSyncAtMillis <= now)

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    fun updateDecision(localContent: String, remoteContent: String, lastHash: String): UpdateDecision {
        if (remoteContent == localContent) return UpdateDecision.UNCHANGED
        val remoteHash = sha256(remoteContent)
        if (lastHash.isNotBlank() && remoteHash == lastHash) return UpdateDecision.UNCHANGED
        if (lastHash.isBlank() || sha256(localContent) != lastHash) return UpdateDecision.CONFLICT
        return UpdateDecision.OVERWRITE
    }

    fun shouldOverwrite(localContent: String, remoteContent: String, lastHash: String): Boolean =
        updateDecision(localContent, remoteContent, lastHash) == UpdateDecision.OVERWRITE

    /** Bound bytes even when Content-Length is absent, incorrect, or chunked. */
    fun readScript(input: InputStream, limit: Int = MAX_SCRIPT_BYTES): String {
        require(limit > 0)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= limit) { "订阅脚本超过大小上限（$limit bytes）" }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    /** UI settings may have been prepared before a queued sync completed. */
    fun applySettings(incoming: ScriptSubscription, current: ScriptSubscription?): ScriptSubscription {
        val cleaned = incoming.copy(url = incoming.url.trim())
        if (current == null || current.url.trim() != cleaned.url) {
            return cleaned.copy(lastSyncAtMillis = 0L, lastError = "", contentHash = "", etag = "", lastModified = "")
        }
        return cleaned.copy(lastSyncAtMillis = current.lastSyncAtMillis, lastError = current.lastError,
            contentHash = current.contentHash, etag = current.etag, lastModified = current.lastModified)
    }
}
