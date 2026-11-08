package com.aniob.app.background

import android.app.Activity
import android.content.Context
import com.aniob.app.overlay.AniobOverlayPill
import java.lang.ref.WeakReference

/**
 * Manages background automation integrity:
 * - On task start: minimizes Aniob (`moveTaskToBack(true)`) so the Accessibility Service
 *   perceives the target application rather than Aniob's own chat UI (no self-observation loop).
 * - Starts the foreground service to protect process priority.
 * - On task completion: post the result notification and bring the chat back to the front.
 *   Background activity starts are restricted since Android 10, but this app already starts
 *   activities from its accessibility service to drive other apps; the same call brings Aniob
 *   itself forward. The notification stays as the fallback when the service is gone.
 */
object AniobBackgroundController {

    private var currentActivityRef: WeakReference<Activity>? = null
    val currentActivity: Activity? get() = currentActivityRef?.get()
    private var pill: AniobOverlayPill? = null

    fun registerActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    fun unregisterActivity(activity: Activity) {
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }

    fun onTaskStarted(context: Context, prompt: String) {
        AniobForegroundService.startService(context, prompt)

        // Status pill 2.0 (UX-3): narration + pause/stop while the user watches.
        pill?.hide()
        pill = null
        // The floating pill covers the app the agent is driving. Opt-in only (Settings); the ongoing
        // notification (progress, Pause, Stop, Grill-Me reply) is the default status surface.
        val pillEnabled = context.getSharedPreferences("aniob_prefs", Context.MODE_PRIVATE)
            .getBoolean("overlay_pill_enabled", false)
        if (pillEnabled) {
            pill = AniobOverlayPill(
                context = context,
                onCancelTask = { AniobForegroundService.requestStop() },
                onPauseToggle = { AniobForegroundService.requestPauseToggle() },
                onOpenApp = { AniobForegroundService.requestOpenApp(context) }
            ).also { it.show(0, "Getting ready") }
        }

        currentActivityRef?.get()?.let { activity ->
            activity.runOnUiThread {
                activity.moveTaskToBack(true)
            }
        }
    }

    /** Narration line for the pill + notification; called from the ViewModel's record path. */
    fun onStepNarrated(stepIndex: Int, narration: String) {
        pill?.updateStep(stepIndex, narration)
        AniobForegroundService.updateProgress(stepIndex, narration)
    }

    fun onTaskPaused(paused: Boolean) {
        pill?.setPaused(paused)
        AniobForegroundService.setPaused(paused)
    }

    fun onTaskFinished(
        context: Context,
        summary: String,
        isSuccess: Boolean = true,
        unverified: Boolean = false
    ) {
        pill?.showDoneAndDismiss()
        pill = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(
                    context,
                    when {
                        isSuccess -> "✓ $summary"
                        unverified -> summary
                        else -> "Task ended: $summary"
                    },
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
            }
            returnToApp(context)
        }
        AniobForegroundService.finishWithNotification(context, summary, isSuccess, unverified)
    }

    /**
     * Brings the chat back when a run ends, so the result is on screen instead of only in the
     * notification. The accessibility service is what moved the target app to the front; the same
     * call moves Aniob back. Skipped when the chat is already the foreground package.
     */
    private fun returnToApp(context: Context) {
        val intent = android.content.Intent(context, com.aniob.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val service = com.aniob.app.service.AniobAccessibilityService.instance
        if (service != null) {
            if (service.currentForegroundPackage() != context.packageName) {
                runCatching { service.startActivity(intent) }
            }
        } else {
            runCatching { context.startActivity(intent) }
        }
    }

    /** Accessibility access was revoked mid-run: drop the pill and post the recovery door (UX-6). */
    fun onAccessibilityLost(context: Context) {
        pill?.hide()
        pill = null
        AniobForegroundService.notifyAccessibilityLost(context)
    }
}

