package com.aniob.app.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aniob.app.MainActivity
import com.aniob.app.R

/**
 * Foreground service ensuring Android's process manager (e.g. FreecessController)
 * does not freeze the app while the user signs in with an external browser,
 * allowing the local loopback OAuth callback listener to receive the redirect.
 */
class AniobSignInService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                onCancelRequested?.invoke()
                teardown()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                teardown()
                return START_NOT_STICKY
            }
            else -> {
                startInForeground()
                return START_NOT_STICKY
            }
        }
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun teardown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.account_title),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.account_waiting_description)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_aniob)
        .setContentTitle(getString(R.string.account_chatgpt))
        .setContentText(getString(R.string.account_waiting_description))
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            getString(R.string.account_cancel),
            PendingIntent.getService(
                this,
                1,
                Intent(this, AniobSignInService::class.java).apply {
                    action = ACTION_CANCEL
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    companion object {
        const val CHANNEL_ID = "aniob_account_sign_in"
        private const val NOTIFICATION_ID = 8780

        const val ACTION_START = "com.aniob.app.auth.START_SIGN_IN"
        const val ACTION_STOP = "com.aniob.app.auth.STOP_SIGN_IN"
        const val ACTION_CANCEL = "com.aniob.app.auth.CANCEL_SIGN_IN"

        @Volatile
        var onCancelRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, AniobSignInService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AniobSignInService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: IllegalStateException) {
                // If the app is already backgrounded/stopped, stop directly
                context.stopService(intent)
            }
        }
    }
}
