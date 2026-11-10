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

    /**
     * Starts the ongoing progress notification. This no longer leaves the chat: submitting a task
     * must not throw the user to the home screen before the agent has decided what the task is.
     */
    fun onTaskStarted(context: Context, prompt: String) {
        AniobForegroundService.startService(context, prompt)
    }

    /**
     * Leaves the chat only once the run is known to drive another app's screen (see
     * `executeTaskPipeline`). A direct-intent task brings its own app to the front, and a task that
     * is still thinking or about to ask a question stays where the user can see it.
     */
    fun backgroundForAutomation(context: Context) {
        val activity = currentActivityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        activity.runOnUiThread { activity.moveTaskToBack(true) }
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
