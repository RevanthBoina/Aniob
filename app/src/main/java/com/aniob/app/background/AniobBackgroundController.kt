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
        currentActivityRef?.get()?.let { activity ->
            activity.runOnUiThread { activity.moveTaskToBack(true) }
        }
    }

    /** Progress is carried by the ongoing notification; no floating overlay is created. */
    fun onStepNarrated(stepIndex: Int, narration: String) {
        AniobForegroundService.updateProgress(stepIndex, narration)
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
        CompletionReporter.report(context, summary, isSuccess, unverified, openChat, wasStopped, resultTitle)
    }

    /** Accessibility access was revoked mid-run: post the recovery notification. */
    fun onAccessibilityLost(context: Context) {
        AniobForegroundService.notifyAccessibilityLost(context)
    }
}
