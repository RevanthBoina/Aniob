package com.aniob.core.narration

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode

/**
 * Turns an action into a short human sentence (UX-3 §1).
 *
 * Pure JVM so the redaction battery runs fast. Narration is read aloud by TalkBack and shown
 * on lock-screen-adjacent surfaces, so secrets must never appear: password / OTP / PIN fields
 * narrate as the field label plus `•••`, never the typed content.
 */
object StepNarration {

    private val SECRET_FIELD = Regex(
        "(?i)(password|passwd|passcode|pin|otp|one[- ]?time|cvv|secret|token|security code)"
    )

    fun narrate(action: AniobAction, node: AniobNode? = null): String = when (action) {
        is AniobAction.Tap -> "Tapping '${label(action.target.describe(), node)}'"
        is AniobAction.LongPress -> "Pressing and holding '${label(action.target.describe(), node)}'"
        is AniobAction.InputText -> {
            val target = label(action.target.describe(), node)
            if (isSecret(node, target)) "Typing into '$target'•••" else "Typing into '$target'"
        }
        is AniobAction.Swipe -> "Scrolling ${action.direction.name.lowercase()}"
        is AniobAction.OpenApp -> "Opening ${prettyPackage(action.packageName)}"
        is AniobAction.SystemKey -> "Going ${action.key.name.lowercase()}"
        is AniobAction.PressKey -> "Going ${action.key.name.lowercase()}"
        is AniobAction.Wait -> "Waiting for the app to load"
        is AniobAction.Finish -> "Done: ${action.summary}"
        is AniobAction.Fail -> "Stopped: ${action.reason}"
        is AniobAction.ConfirmWithUser -> "Asking you to confirm: ${action.message}"
        is AniobAction.GetScreenInfo, is AniobAction.TakeScreenshot -> "Looking at the screen"
        is AniobAction.GetDeviceInfo -> "Checking device status"
        is AniobAction.GetNotifications -> "Reading notifications"
        is AniobAction.GetInstalledApps -> "Listing your apps"
        is AniobAction.Clipboard -> "Using the clipboard"
        is AniobAction.DeviceControl -> DeviceNarration.phrase(action.command)
    }

    private fun isSecret(node: AniobNode?, target: String): Boolean {
        if (SECRET_FIELD.containsMatchIn(target)) return true
        if (node == null) return false
        val hint = listOf(node.text, node.contentDescription, node.viewId).joinToString(" ")
        return SECRET_FIELD.containsMatchIn(hint) || node.className.contains("Password")
    }

    /** Prefers the node's visible label over the internal selector string. */
    private fun label(describe: String, node: AniobNode?): String {
        val fromNode = node?.let {
            it.text.ifBlank { it.contentDescription }.ifBlank { null }
        }
        if (!fromNode.isNullOrBlank()) return fromNode
        return describe
            .removePrefix("som:")
            .removePrefix("id:")
            .removePrefix("text:")
            .removePrefix("desc:")
            .trim('\'', ' ', ':')
    }

    private fun prettyPackage(pkg: String): String {
        val leaf = pkg.substringAfterLast('.')
        if (leaf.isBlank()) return pkg
        return leaf.replaceFirstChar { it.uppercase() }
    }
}
