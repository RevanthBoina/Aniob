package com.aniob.app.background

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** One owner for the end-of-task notification and the narrowly allowed failure return. */
object CompletionReporter {
    private var currentActivityRef: WeakReference<Activity>? = null

    fun registerActivity(activity: Activity) {
        currentActivityRef = WeakReference(activity)
    }

    fun unregisterActivity(activity: Activity) {
        if (currentActivityRef?.get() == activity) currentActivityRef = null
    }

    fun report(
        context: Context,
        summary: String,
        isSuccess: Boolean,
        unverified: Boolean,
        openChat: Boolean,
        wasStopped: Boolean = false,
        resultTitle: String? = null
    ) {
        AniobForegroundService.finishWithNotification(context, summary, isSuccess, unverified, wasStopped, resultTitle)
        if (openChat) Handler(Looper.getMainLooper()).post { returnToChat(context) }
    }

    private fun returnToChat(context: Context) {
        val intent = Intent(context, com.aniob.app.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
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
}
