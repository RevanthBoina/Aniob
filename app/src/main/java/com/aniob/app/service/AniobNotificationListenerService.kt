package com.aniob.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aniob.app.device.AniobNotificationHub

/**
 * Tier-2 background interceptor (zero-LLM).
 *
 * Feeds [AniobNotificationHub] so the agent can read notifications, dismiss them, reply inline,
 * press their buttons, and copy a verification code without opening the messaging app.
 *
 * The original privacy rule still holds and is stronger now that codes are captured: a verification
 * code is held in memory only, masked in every list the model can read, and never logged. Only the
 * notification's package name is logged, never its content.
 */
class AniobNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Seed the hub with what is already in the shade so "copy the OTP" works for a code that
        // arrived while the listener was still binding.
        val active = runCatching { activeNotifications }.getOrNull().orEmpty()
        active.forEach { sbn -> if (sbn.packageName != packageName) AniobNotificationHub.onPosted(sbn, labelOf(sbn.packageName)) }
        Log.i(TAG, "Notification listener connected (${active.size} active)")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        AniobNotificationHub.onPosted(sbn, labelOf(sbn.packageName))
        Log.i(TAG, "Notification captured from ${sbn.packageName}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        AniobNotificationHub.onRemoved(sbn.key)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun labelOf(pkg: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(pkg)

    companion object {
        private const val TAG = "AniobNotifListener"

        /** Bound instance, or null while notification access is off or the service is still binding. */
        @Volatile
        var instance: AniobNotificationListenerService? = null
            private set
    }
}
