package com.aniob.core.domain

/**
 * Pure JVM representation of an interactive screen element.
 * Completely independent of android.view.accessibility.AccessibilityNodeInfo.
 */
data class AniobNode(
    val id: Int,
    val className: String,
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val bounds: AniobRect = AniobRect(0, 0, 0, 0),
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isPassword: Boolean = false,
    val errorText: String = "",
    val isHeading: Boolean = false,
    val paneTitle: String = "",
    val tooltip: String = "",
    val source: GroundingSource = GroundingSource.ACCESSIBILITY,
    /**
     * Compact `<role id=N label='…'>` token produced by the fast observe pipeline. When present it
     * replaces the verbose Set-of-Mark line in the prompt; the id is re-stamped on read so a
     * re-index never leaves stale markup.
     */
    val markup: String = "",
    val isFocused: Boolean = false,
    val isCheckable: Boolean = false
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
    val width: Int get() = bounds.right - bounds.left
    val height: Int get() = bounds.bottom - bounds.top

    fun toOptimizedTokenString(): String {
        if (markup.isNotBlank()) return markup.replaceFirst(Regex("id=\\d+"), "id=$id")
        val viewIdPart = if (viewId.isNotBlank()) " viewId='$viewId'" else ""
        val contentDescPart = if (contentDescription.isNotBlank()) " contentDesc='$contentDescription'" else ""
        val enabledPart = if (!isEnabled) " enabled=false" else ""
        val errorPart = if (errorText.isNotBlank()) " error='$errorText'" else ""
        val headingPart = if (isHeading) " heading=true" else ""
        val panePart = if (paneTitle.isNotBlank()) " pane='$paneTitle'" else ""
        val tooltipPart = if (tooltip.isNotBlank()) " tooltip='$tooltip'" else ""
        val focusPart = if (isFocused) " focused=true" else ""
        val sourcePart = if (source != GroundingSource.ACCESSIBILITY) " source=$source" else ""
        return "[$id] $className$viewIdPart text='$text'$contentDescPart$errorPart$headingPart$panePart$tooltipPart bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] clickable=$isClickable$enabledPart$focusPart$sourcePart"
    }
}

data class AniobRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val isEmpty: Boolean get() = left >= right || top >= bottom
    val area: Int get() = if (isEmpty) 0 else (right - left) * (bottom - top)

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun intersect(other: AniobRect): AniobRect {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (l < r && t < b) AniobRect(l, t, r, b) else AniobRect(0, 0, 0, 0)
    }
}
