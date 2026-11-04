package com.aniob.app.device

import android.app.Notification
import android.service.notification.StatusBarNotification
import com.aniob.core.safety.AniobOtpExtractor
import java.util.ArrayDeque

/**
 * In-memory state fed by `AniobNotificationListenerService`: a short ring of recent notifications
 * (already masked) and a vault holding the newest verification code.
 *
 * Privacy contract (kept from the original OTP reflex): the code is **never** logged, never put in a
 * prompt, never shown in chat. It is kept for [OTP_TTL_MS] and leaves only through `otp_copy` (the
 * clipboard, flagged sensitive) or `otp_paste` (the focused field). Nothing here is persisted.
 */
object AniobNotificationHub {

    const val OTP_TTL_MS: Long = 10 * 60 * 1000L
    private const val RECENT_MAX = 40

    data class Entry(
        val key: String,
        val packageName: String,
        val appLabel: String,
        val title: String,
        /** Body text with any verification code masked. */
        val text: String,
        val postedAt: Long
    )

    data class OtpEntry(val code: String, val packageName: String, val appLabel: String, val receivedAt: Long)

    private val lock = Any()
    private val recent = ArrayDeque<Entry>()
    private var otp: OtpEntry? = null

    fun onPosted(sbn: StatusBarNotification, appLabel: String) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val haystack = listOf(title, text, big).filter { it.isNotBlank() }.joinToString(" ")
        val code = AniobOtpExtractor.extract(haystack)
        synchronized(lock) {
            if (code != null) {
                otp = OtpEntry(code, sbn.packageName, appLabel, sbn.postTime)
            }
            recent.removeAll { it.key == sbn.key }
            recent.addFirst(
                Entry(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = appLabel,
                    title = AniobOtpExtractor.mask(title),
                    text = AniobOtpExtractor.mask(text.ifBlank { big }),
                    postedAt = sbn.postTime
                )
            )
            while (recent.size > RECENT_MAX) recent.removeLast()
        }
    }

    fun onRemoved(key: String) {
        synchronized(lock) { recent.removeAll { it.key == key } }
    }

    fun recent(limit: Int, packageFilter: String? = null): List<Entry> = synchronized(lock) {
        recent.asSequence()
            .filter { packageFilter.isNullOrBlank() || it.packageName.equals(packageFilter, true) || it.appLabel.contains(packageFilter, true) }
            .take(limit.coerceIn(1, 15))
            .toList()
    }

    /** The newest code that is still fresh, or null. */
    fun latestOtp(now: Long = System.currentTimeMillis()): OtpEntry? = synchronized(lock) {
        otp?.takeIf { now - it.receivedAt <= OTP_TTL_MS }
    }

    fun consumeOtp() {
        synchronized(lock) { otp = null }
    }

    fun clear() {
        synchronized(lock) {
            recent.clear()
            otp = null
        }
    }
}
