package com.aniob.app.workflow

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.SemanticTarget
import java.security.MessageDigest

/**
 * Opaque, stable fingerprints for action approvals. Raw typed values and device-command arguments
 * are only used as input to SHA-256; callers store the digest, never those values, in a confirmation
 * request. The target fingerprint deliberately excludes accessibility's recycled node index.
 */
object ApprovalFingerprint {

    fun payload(action: AniobAction, node: AniobNode?): String = sha256(
        "${actionIdentity(action)}|${targetIdentity(action, node)}"
    )

    /** Stable approval destination identity; safe to keep in [com.aniob.app.ui.model.ConfirmRequest]. */
    fun target(action: AniobAction, node: AniobNode?): String = targetIdentity(action, node)

    /** Human-facing label; sensitive target labels and the contents of text fields are not rendered. */
    fun displayTarget(action: AniobAction, node: AniobNode?, sensitive: Boolean): String {
        if (sensitive || node?.isPassword == true) return if (node == null) "Protected action" else "Protected field"
        if (action is AniobAction.InputText) {
            return node?.viewId?.takeIf(String::isNotBlank)?.substringAfterLast('/')
                ?: "Text field"
        }
        return node?.text?.takeIf(String::isNotBlank)
            ?: node?.contentDescription?.takeIf(String::isNotBlank)
            ?: when (val target = action.semanticTarget()) {
                is SemanticTarget.ResourceId -> target.id.substringAfterLast('/')
                is SemanticTarget.SomIndex -> "Screen control ${target.index}"
                is SemanticTarget.Text -> target.text
                is SemanticTarget.ContentDesc -> target.desc
                is SemanticTarget.NormalizedCoordinate -> "Screen control"
                is SemanticTarget.BoundingBox -> "Screen control"
                null -> if (action is AniobAction.DeviceControl) "Device command" else ""
            }
    }

    private fun actionIdentity(action: AniobAction): String = when (action) {
        is AniobAction.Tap -> "tap:${semanticTarget(action.target)}"
        is AniobAction.LongPress -> "long_press:${semanticTarget(action.target)}:${action.durationMs}"
        is AniobAction.InputText -> "input:${semanticTarget(action.target)}:${sha256(action.text)}:${action.clearFirst}:${action.submit}"
        is AniobAction.Swipe -> "swipe:${action.direction}:${action.distancePx}:${action.container?.let(::semanticTarget).orEmpty()}"
        is AniobAction.OpenApp -> "open_app:${action.packageName}:${action.activityName.orEmpty()}"
        is AniobAction.GetScreenInfo -> "get_screen_info"
        is AniobAction.TakeScreenshot -> "take_screenshot"
        is AniobAction.GetDeviceInfo -> "get_device_info"
        is AniobAction.GetNotifications -> "get_notifications:${action.filterPackage.orEmpty()}"
        is AniobAction.GetInstalledApps -> "get_installed_apps"
        is AniobAction.Clipboard -> "clipboard:${action.operation}:${action.text?.let(::sha256).orEmpty()}"
        is AniobAction.SystemKey -> "system_key:${action.key}"
        is AniobAction.PressKey -> "press_key:${action.key}"
        is AniobAction.Wait -> "wait:${action.durationMs}"
        is AniobAction.ConfirmWithUser -> "confirm:${sha256(action.message)}:${action.riskLevel}"
        is AniobAction.Finish -> "finish:${sha256(action.summary)}"
        is AniobAction.DeviceControl -> buildString {
            append("device:").append(action.command.wire)
            action.args.toSortedMap().forEach { (key, value) ->
                append('|').append(key).append('=').append(sha256(value))
            }
        }
        is AniobAction.Fail -> "fail:${sha256(action.reason)}"
        else -> "${action.toolName}:${sha256(action.toString())}"
    }

    private fun targetIdentity(action: AniobAction, node: AniobNode?): String {
        val semantic = action.semanticTarget()?.let(::semanticTarget).orEmpty()
        val grounded = node?.let {
            val anchor = it.viewId.takeIf(String::isNotBlank)
                ?: "${it.className}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
            "node:$anchor:${sha256(it.text)}:${sha256(it.contentDescription)}:${it.isPassword}"
        }.orEmpty()
        return sha256("target:$semantic|grounded:$grounded")
    }

    private fun semanticTarget(target: SemanticTarget): String = when (target) {
        is SemanticTarget.SomIndex -> "som:${target.index}"
        is SemanticTarget.ResourceId -> "resource:${target.id}"
        is SemanticTarget.Text -> "text:${sha256(target.text)}:${target.exact}"
        is SemanticTarget.ContentDesc -> "description:${sha256(target.desc)}:${target.exact}"
        is SemanticTarget.NormalizedCoordinate -> "coordinate:${target.normX},${target.normY}"
        is SemanticTarget.BoundingBox -> "box:${target.left},${target.top},${target.right},${target.bottom}"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
