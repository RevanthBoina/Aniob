package com.aniob.core.execution

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget

/**
 * System navigation targets are not app-window nodes: the back/home/recents controls live in a
 * system window, so grounding a tap against the app tree can never resolve them and the step used to
 * die with "Action not dispatched". Rewrite them to the global key action the platform provides.
 *
 * Matching is by *role*, not by one vendor's resource id: the id tail, the label, the content
 * description and the class name are all checked, and any package that owns the system UI counts.
 */
object SystemNavTargets {

    private val SYSTEM_PACKAGES = setOf("com.android.systemui", "com.android.internal.systemui", "android")

    private val ROLES: Map<KeyType, Set<String>> = mapOf(
        KeyType.HOME to setOf("home", "home_button", "home button", "home key", "launcher"),
        KeyType.BACK to setOf("back", "back_button", "back button", "navigate up", "return"),
        KeyType.RECENTS to setOf("recent_apps", "recents", "recent apps", "recent_apps_button", "overview", "app switch")
    )

    private fun keyFor(text: String): KeyType? {
        val v = text.trim().lowercase().substringAfterLast(":id/").substringAfterLast('/').trim(' ', '_')
        if (v.isBlank()) return null
        return ROLES.entries.firstOrNull { (_, names) -> v in names || names.any { v == it.replace(' ', '_') } }?.key
    }

    /** Rewrite from the action's own target (resource id or literal label). */
    fun rewrite(action: AniobAction): AniobAction {
        if (action !is AniobAction.Tap && action !is AniobAction.LongPress) return action
        val raw = when (val t = action.semanticTarget()) {
            is SemanticTarget.ResourceId -> t.id
            is SemanticTarget.Text -> t.text
            is SemanticTarget.ContentDesc -> t.desc
            else -> return action
        }
        val key = keyFor(raw) ?: return action
        return AniobAction.PressKey(key, "System navigation target '${raw.trim()}' -> $key key")
    }

    /** Rewrite once the live node is known: system-UI ownership + role in id/label/description/class. */
    fun rewriteWithNode(action: AniobAction, node: AniobNode?): AniobAction {
        if (action !is AniobAction.Tap && action !is AniobAction.LongPress) return action
        val n = node ?: return action
        val systemOwned = SYSTEM_PACKAGES.any { n.viewId.startsWith("$it:") } ||
            n.className.contains("NavigationBar", true) || n.paneTitle.contains("navigation", true)
        if (!systemOwned) return action
        val key = listOf(n.viewId, n.text, n.contentDescription).firstNotNullOfOrNull { keyFor(it) } ?: return action
        return AniobAction.PressKey(key, "System navigation node '${n.viewId.ifBlank { n.text }}' -> $key key")
    }
}
