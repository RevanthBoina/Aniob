package com.aniob.app.background

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

/**
 * Owns background-task startup and progress. Completion presentation is delegated to
 * [CompletionReporter], which posts one result notification and only reopens chat when needed.
 */
object AniobBackgroundController {
    private var currentActivityRef: WeakReference<Activity>? = null
    val currentActivity: Activity? get() = currentActivityRef?.get()

    /** Callback invoked when user requests stop via the floating overlay. */
    var onStopCallback: (() -> Unit)? = null

    fun registerActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        CompletionReporter.registerActivity(activity)
    }

    fun unregisterActivity(activity: Activity) {
        if (currentActivityRef?.get() == activity) currentActivityRef = null
        CompletionReporter.unregisterActivity(activity)
    }

    fun onTaskStarted(context: Context, prompt: String) {
        AniobForegroundService.startService(context, prompt)
        com.aniob.app.service.AniobAccessibilityService.instance?.showFloatingOverlay(0, prompt)
    }

    /** Progress is carried by ongoing notification and the accessibility floating status overlay. */
    fun onStepNarrated(stepIndex: Int, narration: String) {
        AniobForegroundService.updateProgress(stepIndex, narration)
        com.aniob.app.service.AniobAccessibilityService.instance?.updateFloatingOverlay(stepIndex, narration)
    }

    fun onTaskPaused(paused: Boolean) {
        AniobForegroundService.setPaused(paused)
    }

    fun onTaskFinished(
        context: Context,
        summary: String,
        isSuccess: Boolean = true,
        unverified: Boolean = false,
        openChat: Boolean = !isSuccess || unverified,
        wasStopped: Boolean = false,
        resultTitle: String? = null
    ) {
        com.aniob.app.service.AniobAccessibilityService.instance?.hideFloatingOverlay()
        CompletionReporter.report(context, summary, isSuccess, unverified, openChat, wasStopped, resultTitle)
    }

    /** Accessibility access was revoked mid-run: post the recovery notification. */
    fun onAccessibilityLost(context: Context) {
        AniobForegroundService.notifyAccessibilityLost(context)
    }
}
