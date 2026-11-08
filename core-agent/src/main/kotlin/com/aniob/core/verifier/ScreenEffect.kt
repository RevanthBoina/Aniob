package com.aniob.core.verifier

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState

/**
 * "Did that action change the screen?" — judged by CONTENT, never by a cached hash or a Set-of-Mark id.
 *
 * Two independent identity signals already existed (a per-node tree hash and a package/activity/
 * structure hash) and both could be stale or coarse, so a tap that really worked was reported as
 * "no effect". This compares the meaningful nodes before/after, pairing them by role+id+label:
 *
 *  - same label, new/missing      -> added / removed
 *  - same label, changed state    -> toggled (switch flipped, row selected, keyboard opened)
 *  - same label, moved            -> moved (scroll, re-layout)
 *  - different foreground package -> place changed
 *
 * A SMALL_CHANGE (focus, keyboard, one row) still counts as an effect: refusing it is what made
 * search boxes and toggle rows look like dead taps. [customSurface] marks screens the tree cannot
 * describe (WebView/Canvas/Flutter/video): silence there is not evidence of failure, so the caller
 * must not fail the step on it.
 */
object ScreenEffect {
    enum class Kind { PLACE_CHANGED, DONE, SMALL_CHANGE, NO_EFFECT }

    data class Result(
        val kind: Kind,
        val added: Int = 0,
        val removed: Int = 0,
        val moved: Int = 0,
        val toggled: Int = 0,
        val imeChanged: Boolean = false,
        /** Tree cannot describe this surface; "nothing changed" here proves nothing. */
        val customSurface: Boolean = false
    ) {
        val changed: Boolean get() = kind != Kind.NO_EFFECT
        /** The screen moved in a way that matches "the action worked". */
        val major: Boolean get() = kind == Kind.PLACE_CHANGED || kind == Kind.DONE
    }

    private fun key(n: AniobNode) = "${n.className}|${n.viewId}|${n.text}|${n.contentDescription}"
    private fun state(n: AniobNode) = "${n.isSelected}|${n.isChecked}|${n.isEnabled}|${n.isEditable}"
    private fun place(n: AniobNode) = "${n.bounds.left},${n.bounds.top}"
    private fun meaningful(n: AniobNode) =
        n.isVisibleToUser && (n.text.isNotBlank() || n.contentDescription.isNotBlank() || n.viewId.isNotBlank())

    fun between(before: AniobScreenState, after: AniobScreenState): Result {
        if (before.packageName.isNotBlank() && after.packageName.isNotBlank() &&
            before.packageName != after.packageName
        ) return Result(Kind.PLACE_CHANGED)

        val leftBefore = before.nodes.filter(::meaningful).groupingBy(::key).eachCount().toMutableMap()
        val afterCounts = after.nodes.filter(::meaningful).groupingBy(::key).eachCount()
        var added = 0
        for ((k, n) in afterCounts) {
            val had = leftBefore[k] ?: 0
            if (n > had) added += n - had
            if (had > 0) leftBefore[k] = (had - n).coerceAtLeast(0)
        }
        val removed = leftBefore.values.sum()

        val beforeState = before.nodes.filter(::meaningful).associate { key(it) to state(it) }
        val beforePlace = before.nodes.filter(::meaningful).associate { key(it) to place(it) }
        var toggled = 0
        var moved = 0
        for (n in after.nodes.filter(::meaningful)) {
            val k = key(n)
            when {
                beforeState[k]?.let { it != state(n) } == true -> toggled++
                beforePlace[k]?.let { it != place(n) } == true -> moved++
            }
        }
        val ime = before.hasImeWindow != after.hasImeWindow
        val total = maxOf(before.nodes.size, after.nodes.size, 1)
        val changes = added + removed + toggled
        val blind = (before.nodes + after.nodes).any {
            val c = it.className
            c.contains("WebView", true) || c.contains("SurfaceView", true) || c.contains("Canvas", true) ||
                c.contains("Flutter", true) || c.contains("TextureView", true) || c.contains("GLSurface", true)
        }
        val kind = when {
            changes == 0 && !ime && moved == 0 -> Kind.NO_EFFECT
            changes >= maxOf(4, total / 3) -> Kind.DONE
            else -> Kind.SMALL_CHANGE
        }
        return Result(kind, added, removed, moved, toggled, ime, customSurface = blind && kind == Kind.NO_EFFECT)
    }
}
