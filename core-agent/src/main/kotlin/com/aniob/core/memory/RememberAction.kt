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
        if (inputIndex == null && textIndex == null) return this
        return copy(
            inputText = inputIndex?.let { RememberTemplate.placeholder(it) } ?: inputText,
            text = textIndex?.let { RememberTemplate.placeholder(it) } ?: text
        )
    }

    /** This descriptor with `{PARAM_n}` filled from the new prompt; null when one is missing. */
    fun injected(params: List<String>): RememberAction? = copy(
        inputText = RememberTemplate.inject(inputText, params) ?: return null,
        text = RememberTemplate.inject(text, params) ?: return null
    )

    companion object {
        const val TAP = "tap"
        const val LONG_PRESS = "long_press"
        const val INPUT = "input"
        const val SCROLL = "scroll"
        const val BACK = "back"
        const val OPEN_APP = "open_app"

        /** Snapshot of a dispatched action; unsupported actions return null and are never cached. */
        fun fromAction(action: AniobAction, node: AniobNode?, screen: AniobScreenState): RememberAction? {
            val target = action.semanticTarget()
            val viewId = node?.viewId.orEmpty().ifBlank { (target as? SemanticTarget.ResourceId)?.id.orEmpty() }
            val text = node?.text.orEmpty().ifBlank { (target as? SemanticTarget.Text)?.text.orEmpty() }
            val desc = node?.contentDescription.orEmpty()
                .ifBlank { (target as? SemanticTarget.ContentDesc)?.desc.orEmpty() }
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
