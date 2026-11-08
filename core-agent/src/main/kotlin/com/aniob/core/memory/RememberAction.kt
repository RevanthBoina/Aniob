package com.aniob.core.memory

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget

/**
 * One replayable action, stored by stable identity rather than by element index.
 *
 * Element ids are re-assigned on every observation, so a cached `som_index` would point at a
 * different widget on the next screen. `viewId`, text and description survive that re-index; the
 * normalised centre is the last resort for widgets that have none of them.
 */
data class RememberAction(
    val kind: String,
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val inputText: String = "",
    val clearFirst: Boolean = false,
    val direction: String = "",
    val normalizedX: Double = -1.0,
    val normalizedY: Double = -1.0
) {
    val isUsable: Boolean
        get() = kind.isNotBlank() && (viewId.isNotBlank() || text.isNotBlank() ||
            contentDescription.isNotBlank() || (normalizedX >= 0.0 && normalizedY >= 0.0))

    /**
     * This descriptor with the prompt's own values swapped for `{PARAM_n}`.
     *
     * Only an exact match counts: a value that merely *contains* the parameter (a keypad digit
     * inside a time) is part of the app's layout, not of the prompt, and substituting it would
     * replay the wrong tap.
     */
    fun parameterised(parsed: RememberTemplate.Parsed): RememberAction {
        if (!parsed.hasParams) return this
        val inputIndex = parsed.indexOfValue(inputText)
        val textIndex = parsed.indexOfValue(text)
        val descIndex = parsed.indexOfValue(contentDescription)
        if (inputIndex == null && textIndex == null && descIndex == null) return this
        return copy(
            viewId = if (textIndex != null || descIndex != null) "" else viewId,
            inputText = inputIndex?.let { RememberTemplate.placeholder(it) } ?: inputText,
            text = textIndex?.let { RememberTemplate.placeholder(it) } ?: text,
            contentDescription = descIndex?.let { RememberTemplate.placeholder(it) } ?: contentDescription
        )
    }

    /** This descriptor with `{PARAM_n}` filled from the new prompt; null when one is missing. */
    fun injected(params: List<String>): RememberAction? {
        val newIn = RememberTemplate.inject(inputText, params) ?: return null
        val newText = RememberTemplate.inject(text, params) ?: return null
        val newDesc = RememberTemplate.inject(contentDescription, params) ?: return null
        return copy(
            inputText = newIn,
            text = newText,
            contentDescription = newDesc
        )
    }

    /** Converts this descriptor to an [AniobAction] using its semantic identity. */
    fun toAction(): AniobAction? {
        if (kind == BACK) return AniobAction.PressKey(KeyType.BACK)
        if (kind == SCROLL) {
            val dir = runCatching { com.aniob.core.domain.SwipeDirection.valueOf(direction) }
                .getOrDefault(com.aniob.core.domain.SwipeDirection.UP)
            return AniobAction.Swipe(direction = dir, distancePx = 600)
        }
        if (kind == OPEN_APP) {
            return if (text.isBlank()) null else AniobAction.OpenApp(packageName = text)
        }
        val target = when {
            viewId.isNotBlank() -> SemanticTarget.ResourceId(viewId)
            text.isNotBlank() -> SemanticTarget.Text(text, exact = true)
            contentDescription.isNotBlank() -> SemanticTarget.ContentDesc(contentDescription, exact = true)
            else -> return null
        }
        return when (kind) {
            TAP -> AniobAction.Tap(target)
            LONG_PRESS -> AniobAction.LongPress(target)
            INPUT -> inputText.takeIf { it.isNotBlank() }?.let {
                AniobAction.InputText(target, it, clearFirst = clearFirst)
            }
            else -> null
        }
    }

    companion object {
        const val TAP = "tap"
        const val LONG_PRESS = "long_press"
        const val INPUT = "input"
        const val SCROLL = "scroll"
        const val BACK = "back"
        const val OPEN_APP = "open_app"

        /** Snapshot of a dispatched action; unsupported actions return null and are never cached. */
        fun fromAction(action: AniobAction, node: AniobNode?, screen: AniobScreenState): RememberAction? {
            if (node?.isPassword == true) return null
            if (action is AniobAction.InputText && (action.text.length in 4..8 && action.text.all { it.isDigit() })) {
                return null
            }
            val target = action.semanticTarget()
            val viewId = node?.viewId.orEmpty().ifBlank { (target as? SemanticTarget.ResourceId)?.id.orEmpty() }
            val rawText = node?.text.orEmpty().ifBlank { (target as? SemanticTarget.Text)?.text.orEmpty() }
            val text = if (action is AniobAction.InputText) "" else com.aniob.core.perception.BurstTargetGuard.normalizeLabel(rawText)
            val desc = com.aniob.core.perception.BurstTargetGuard.normalizeLabel(
                node?.contentDescription.orEmpty().ifBlank { (target as? SemanticTarget.ContentDesc)?.desc.orEmpty() }
            )
            val center = node?.let { normalizedCenter(it, screen) }

            val descriptor = when (action) {
                is AniobAction.Tap -> RememberAction(
                    kind = TAP, viewId = viewId, text = text, contentDescription = desc,
                    normalizedX = center?.first ?: -1.0, normalizedY = center?.second ?: -1.0
                )
                is AniobAction.LongPress -> RememberAction(
                    kind = LONG_PRESS, viewId = viewId, text = text, contentDescription = desc,
                    normalizedX = center?.first ?: -1.0, normalizedY = center?.second ?: -1.0
                )
                is AniobAction.InputText -> RememberAction(
                    kind = INPUT, viewId = viewId, text = text, contentDescription = desc,
                    inputText = action.text, clearFirst = action.clearFirst,
                    normalizedX = center?.first ?: -1.0, normalizedY = center?.second ?: -1.0
                )
                is AniobAction.Swipe -> RememberAction(kind = SCROLL, direction = action.direction.name)
                // Only BACK is replayable: HOME or RECENTS would not reproduce the transition.
                is AniobAction.PressKey -> RememberAction(kind = BACK).takeIf { action.key == KeyType.BACK }
                is AniobAction.SystemKey -> RememberAction(kind = BACK).takeIf { action.key == KeyType.BACK }
                is AniobAction.OpenApp -> RememberAction(kind = OPEN_APP, text = action.packageName)
                else -> null
            }
            return descriptor?.takeIf {
                it.isUsable || it.kind == BACK || it.kind == SCROLL || it.kind == OPEN_APP
            }
        }

        /** Centre of [node] as fractions of the captured viewport; both in `[0, 1]`. */
        fun normalizedCenter(node: AniobNode, screen: AniobScreenState): Pair<Double, Double> {
            val viewport = viewportOf(screen)
            val x = ((node.bounds.left + node.bounds.right) / 2.0 / viewport.first).coerceIn(0.0, 1.0)
            val y = ((node.bounds.top + node.bounds.bottom) / 2.0 / viewport.second).coerceIn(0.0, 1.0)
            return x to y
        }

        /** Largest bounds seen on the screen, with sensible floors for an empty capture. */
        fun viewportOf(screen: AniobScreenState): Pair<Double, Double> {
            val width = screen.nodes.maxOfOrNull { it.bounds.right }?.toDouble() ?: 0.0
            val height = screen.nodes.maxOfOrNull { it.bounds.bottom }?.toDouble() ?: 0.0
            return width.coerceAtLeast(1.0) to height.coerceAtLeast(1.0)
        }
    }
}
