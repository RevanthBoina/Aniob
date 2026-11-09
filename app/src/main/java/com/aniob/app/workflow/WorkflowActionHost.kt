package com.aniob.app.workflow

import android.app.Activity
import android.content.ActivityNotFoundException
import com.aniob.core.workflow.OperationIdentity
import com.aniob.core.workflow.WorkflowResult
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkflowActionHost: Lifecycle-bound UI-side launcher for native and Custom Tabs effects.
 *
 * Invariant: Atomically claims an effect before launch to guarantee exactly-once
 * dispatch without replays on Compose recomposition.
 */
class WorkflowActionHost(private val platformTools: PlatformTools) {

    /**
     * Optional one-line audit sink for launch decisions (claim/accept/fail). Set once by
     * [com.aniob.app.AniobApplication]; purely diagnostic, never changes claim semantics.
     */
    @Volatile
    var onAudit: ((String) -> Unit)? = null

    private var attachedActivity: Activity? = null
    private val claimedActionIds = ConcurrentHashMap.newKeySet<String>()

    private fun audit(line: String) {
        runCatching { onAudit?.invoke(line) }
    }

    fun attach(activity: Activity) {
        attachedActivity = activity
    }

    fun detach() {
        attachedActivity = null
    }

    /**
     * Atomically claims and launches an effect.
     * Returns true if successfully claimed and launched.
     */
    fun dispatchLaunch(
        spec: LaunchSpec,
        identity: OperationIdentity,
        onReceipt: (WorkflowResult) -> Unit
    ): Boolean {
        val actionId = when (spec) {
            is LaunchSpec.ActivityIntent -> spec.actionId
            is LaunchSpec.CustomTabs -> spec.actionId
            is LaunchSpec.Sharesheet -> spec.actionId
        }

        // Exactly-once guarantee across recomposition
        if (!claimedActionIds.add(actionId)) {
            audit("claim-rejected actionId=$actionId (already dispatched)")
            return false
        }

        val activity = attachedActivity
        if (activity == null) {
            claimedActionIds.remove(actionId)
            onReceipt(WorkflowResult.Failed("No active UI host attached to launch external effect."))
            return false
        }

        try {
            when (spec) {
                is LaunchSpec.ActivityIntent -> {
                    activity.startActivity(spec.intent)
                    onReceipt(WorkflowResult.LaunchAccepted(receiptId = "launch_${actionId}"))
                }
                is LaunchSpec.CustomTabs -> {
                    val intent = platformTools.buildCustomTabsIntent(spec.url, spec.browserPackage)
                    activity.startActivity(intent)
                    onReceipt(WorkflowResult.LaunchAccepted(receiptId = "tabs_${actionId}"))
                }
                is LaunchSpec.Sharesheet -> {
                    activity.startActivity(spec.intent)
                    onReceipt(WorkflowResult.LaunchAccepted(receiptId = "share_${actionId}"))
                }
            }
            audit("accepted actionId=$actionId")
            return true
        } catch (e: ActivityNotFoundException) {
            audit("failed actionId=$actionId reason=activity-not-found")
            onReceipt(WorkflowResult.Failed("No compatible application or browser found to handle this action."))
            return false
        } catch (e: Exception) {
            audit("failed actionId=$actionId reason=${e.javaClass.simpleName}")
            onReceipt(WorkflowResult.Failed("Launch failed: ${e.message}"))
            return false
        }
    }

    fun clearClaimed(actionId: String) {
        claimedActionIds.remove(actionId)
    }

    fun clearAll() {
        claimedActionIds.clear()
    }
}
