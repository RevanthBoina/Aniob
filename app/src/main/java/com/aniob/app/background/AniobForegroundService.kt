package com.aniob.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aniob.app.MainActivity
import com.aniob.app.R

/**
 * Foreground service ensuring the OS does not throttle or kill Aniob during
 * multi-step autonomous UI background tasks.
 * Uses Android 14+ FOREGROUND_SERVICE_SPECIAL_USE declaration.
 *
 * UX-6: progress, approval, and result channels, a progress bar with narration, Pause/Stop actions,
 * and a proper app icon. Verified success stays in the background; failed or unverified runs may
 * reopen chat so the user can inspect them.
 */
class AniobForegroundService : Service() {

    companion object {
        const val CHANNEL_PROGRESS = "aniob_progress"
        const val CHANNEL_CONFIRMATIONS = "aniob_confirmations"
        const val CHANNEL_DONE = "aniob_done"
        const val CHANNEL_TASK_START = "aniob_task_start"

        private const val NOTIFICATION_ID = 8765
        private const val DONE_NOTIFICATION_ID = 8766
        private const val RECOVERY_NOTIFICATION_ID = 8767

        const val ACTION_START = "com.aniob.app.action.START_TASK"
        const val ACTION_STOP = "com.aniob.app.action.STOP_TASK"
        const val ACTION_PAUSE = "com.aniob.app.action.PAUSE_TASK"
        const val ACTION_CONFIRM_APPROVE = "com.aniob.app.action.CONFIRM_APPROVE"
        const val ACTION_CONFIRM_DENY = "com.aniob.app.action.CONFIRM_DENY"
        const val EXTRA_TASK_PROMPT = "extra_task_prompt"
        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_REQUEST_ID = "extra_request_id"

        private const val CONFIRM_NOTIFICATION_ID = 8768
        private const val WAITING_NOTIFICATION_ID = 8769
        private const val TASK_START_NOTIFICATION_ID = 8770
        /** Lets the ViewModel cancel the running task when the notification Stop action is tapped. */
        @Volatile var onStopRequestedFromNotification: (() -> Unit)? = null

        /** Lets the ViewModel flip its cooperative pause flag from the notification. */
        @Volatile var onPauseToggleFromNotification: (() -> Unit)? = null

        /** Carries the user's approval decision back with mandatory task and request IDs to prevent stale approvals. */
        @Volatile var onConfirmDecisionWithIds: ((com.aniob.app.ui.model.ConfirmDecision, String?, String?) -> Unit)? = null

        @Volatile private var currentPrompt: String = ""
        @Volatile private var currentStep: Int = 0
        @Volatile private var currentNarration: String = ""
        @Volatile private var isPaused: Boolean = false
        @Volatile private var isStopRequested: Boolean = false

        fun startService(context: Context, taskPrompt: String) {
            isStopRequested = false
            currentPrompt = taskPrompt
            currentStep = 0
            currentNarration = "Getting ready"
            isPaused = false
            notifyTaskStarted(context, taskPrompt)
            val intent = Intent(context, AniobForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TASK_PROMPT, taskPrompt)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.w("AniobForegroundService", "startForegroundService failed: ${e.message}")
            }
        }

        private fun notifyTaskStarted(context: Context, taskPrompt: String) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, manager)
            val open = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://chat")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val openPi = PendingIntent.getActivity(
                context, 11, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_TASK_START)
                .setContentTitle("Aniob is starting")
                .setContentText(taskPrompt)
                .setStyle(NotificationCompat.BigTextStyle().bigText(taskPrompt))
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            manager.notify(TASK_START_NOTIFICATION_ID, notification)
        }

        private fun clearTaskStart(context: Context) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(TASK_START_NOTIFICATION_ID)
        }

        fun stopService(context: Context) {
            isStopRequested = true
            instances.forEach { service ->
                try {
                    service.stopForeground(STOP_FOREGROUND_REMOVE)
                    service.stopSelf()
                } catch (e: Exception) {
                    android.util.Log.d("AniobForegroundService", "instance teardown error: ${e.message}")
                }
            }
        }

        /** Notification Stop action entry point. */
        fun requestStop() {
            onStopRequestedFromNotification?.invoke()
        }

        /** Notification Pause (Resume) action entry point. */
        fun requestPauseToggle() {
            onPauseToggleFromNotification?.invoke()
        }

        /** Mirrors narration + step progress into the live notification (UX-3 → UX-6). */
        fun updateProgress(stepIndex: Int, narration: String) {
            currentStep = stepIndex
            currentNarration = narration
            instances.forEach { it.refreshNotification() }
        }

        fun setPaused(paused: Boolean) {
            isPaused = paused
            instances.forEach { it.refreshNotification() }
        }

        /** Completion notification on the Done channel, with a deep link into the result. */
        fun finishWithNotification(
            context: Context,
            summary: String,
            isSuccess: Boolean = true,
            unverified: Boolean = false,
            wasStopped: Boolean = false,
            resultTitle: String? = null
        ) {
            clearTaskStart(context)
            stopService(context)
            when {
                isSuccess -> notifyFinished(context, summary, resultTitle ?: "Done: $currentPrompt")
                // Not a failure: the steps ran, the outcome could not be machine-checked.
                unverified -> notifyFinished(context, summary, resultTitle ?: "Done, not verified: $currentPrompt")
                wasStopped -> notifyFinished(context, summary, resultTitle ?: "Stopped: $currentPrompt")
                else -> notifyFailure(context, summary, resultTitle)
            }
        }

        private fun notifyFinished(
            context: Context,
            summary: String,
            title: String = "✓ Done: $currentPrompt"
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://result")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val pi = PendingIntent.getActivity(
                context, 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle(title)
                .setContentText(summary)
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(DONE_NOTIFICATION_ID, notification)
        }

        /** Failure variant: one result notification with a View affordance (UX-6). */
        fun notifyFailure(context: Context, summary: String, title: String? = null) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val view = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://result")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val viewPi = PendingIntent.getActivity(
                context, 3, view,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle(title ?: "Needs attention: $currentPrompt")
                .setContentText(summary)
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(viewPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, "View", viewPi)
                .build()
            nm.notify(DONE_NOTIFICATION_ID, notification)
        }

        /**
         * Approval reaches a minimized user on the high-priority channel, with inline Approve/Deny.
         * Silence leaves the action paused; only an explicit decision or Stop resolves the wait.
         */
        fun notifyConfirmation(context: Context, request: com.aniob.app.ui.model.ConfirmRequest) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)

            val fullScreen = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://confirm")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val fullScreenPi = PendingIntent.getActivity(
                context, 7, fullScreen,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val approvePi = PendingIntent.getService(
                context, 8,
                Intent(context, AniobForegroundService::class.java).apply {
                    action = ACTION_CONFIRM_APPROVE
                    putExtra(EXTRA_TASK_ID, request.taskId)
                    putExtra(EXTRA_REQUEST_ID, request.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val denyPi = PendingIntent.getService(
                context, 9,
                Intent(context, AniobForegroundService::class.java).apply {
                    action = ACTION_CONFIRM_DENY
                    putExtra(EXTRA_TASK_ID, request.taskId)
                    putExtra(EXTRA_REQUEST_ID, request.id)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_CONFIRMATIONS)
                .setContentTitle(request.title)
                .setContentText(request.what)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${request.what}\n${request.why}"))
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false)
                .addAction(0, "Approve once", approvePi)
                .addAction(0, "Deny", denyPi)
                .build()
            nm.notify(CONFIRM_NOTIFICATION_ID, notification)
        }

        /** Clears the approval door once a decision exists. */
        fun clearConfirmation(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(CONFIRM_NOTIFICATION_ID)
        }

        fun notifyWaitingForUser(context: Context, target: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val continueIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://chat")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val continuePi = PendingIntent.getActivity(
                context, 10, continueIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle("Task waiting on $target")
                .setContentText("Tap to return to Aniob and continue")
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(continuePi)
                .setAutoCancel(true)
                .build()
            nm.notify(WAITING_NOTIFICATION_ID, notification)
        }

        fun clearWaitingNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.cancel(WAITING_NOTIFICATION_ID)
        }

        /** Accessibility revoked mid-run: recovery door with [Re-enable] [Stop task] (UX-6). */
        fun notifyAccessibilityLost(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            ensureChannels(context, nm)
            val reEnable = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("aniob://chat")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val reEnablePi = PendingIntent.getActivity(
                context, 4, reEnable,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val stopIntent = Intent(context, AniobForegroundService::class.java).apply { action = ACTION_STOP }
            val stopPi = PendingIntent.getService(
                context, 5, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle("Aniob lost access")
                .setContentText("Re-enable accessibility to continue, or stop the task.")
                .setSmallIcon(R.drawable.ic_stat_aniob)
                .setContentIntent(reEnablePi)
                .setAutoCancel(false)
                .addAction(0, "Re-enable", reEnablePi)
                .addAction(0, "Stop task", stopPi)
                .build()
            nm.notify(RECOVERY_NOTIFICATION_ID, notification)
        }

        private val instances = mutableSetOf<AniobForegroundService>()

        internal fun ensureChannels(context: Context, manager: NotificationManager) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Task progress", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Live narration while Aniob works"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_CONFIRMATIONS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Risky steps that need your approval"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Task results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Completed, failed, or interrupted tasks"
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_TASK_START, "Task start previews", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows the task Aniob is about to start"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        instances.add(this)
        createNotificationChannels()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            android.util.Log.w("AniobForegroundService", "startForeground failed in onCreate: ${e.message}")
        }
        if (isStopRequested) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                android.util.Log.d("AniobForegroundService", "early stop in onCreate error: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        instances.remove(this)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            android.util.Log.d("AniobForegroundService", "onDestroy stopForeground error: ${e.message}")
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prompt = intent?.getStringExtra(EXTRA_TASK_PROMPT) ?: currentPrompt
        if (prompt.isNotBlank()) currentPrompt = prompt

        if (isStopRequested || intent?.action == ACTION_STOP) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID)
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                android.util.Log.d("AniobForegroundService", "stopForeground in onStartCommand error: ${e.message}")
            }
            onStopRequestedFromNotification?.invoke()
            onStopRequestedFromNotification = null
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_PAUSE -> {
                val toggleHandler = onPauseToggleFromNotification
                if (toggleHandler != null) {
                    // ViewModel.togglePause() flips pauseRequested and calls setPaused(pauseRequested)
                    toggleHandler.invoke()
                } else {
                    isPaused = !isPaused
                    refreshNotification()
                }
                return START_STICKY
            }
            ACTION_CONFIRM_APPROVE -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                clearConfirmation(this)
                if (taskId != null && reqId != null) {
                    onConfirmDecisionWithIds?.invoke(com.aniob.app.ui.model.ConfirmDecision.APPROVE_ONCE, taskId, reqId)
                }
                return START_STICKY
            }
            ACTION_CONFIRM_DENY -> {
                val taskId = intent?.getStringExtra(EXTRA_TASK_ID)
                val reqId = intent?.getStringExtra(EXTRA_REQUEST_ID)
                clearConfirmation(this)
                if (taskId != null && reqId != null) {
                    onConfirmDecisionWithIds?.invoke(com.aniob.app.ui.model.ConfirmDecision.DENY, taskId, reqId)
                }
                return START_STICKY
            }
            else -> {
                refreshNotification()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannels(this, manager)
    }

    internal fun refreshNotification() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // Service not in foreground state; the next startForeground will pick it up.
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("aniob://tracker")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AniobForegroundService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(this, AniobForegroundService::class.java).apply { action = ACTION_PAUSE }
        val pausePendingIntent = PendingIntent.getService(
            this, 6, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle(currentPrompt.ifBlank { "Aniob is working" })
            .setContentText(currentNarration)
            .setSmallIcon(R.drawable.ic_stat_aniob)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .addAction(0, if (isPaused) "▶ Resume" else "⏸ Pause", pausePendingIntent)
            .addAction(0, "■ Stop", stopPendingIntent)
            .build()
    }
}
