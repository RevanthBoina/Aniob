package com.aniob.core.perception

import com.aniob.core.domain.AniobRect

/**
 * Platform-neutral snapshot of one node in the live view hierarchy.
 *
 * The :app layer walks `AccessibilityNodeInfo` and builds this tree, so every pruning, merging and
 * hashing rule below is pure JVM code that can be unit-tested without an emulator.
 *
 * [platformIndex] is the handle the platform layer can use to reach the original node again
 * (it stores the node references in traversal order); `-1` means "reference not retained".
 */
data class ObserveSnapshot(
    val className: String = "",
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val bounds: AniobRect = AniobRect(0, 0, 0, 0),
    val isVisible: Boolean = true,
    val isClickable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true,
    val isPassword: Boolean = false,
    val isHeading: Boolean = false,
    val errorText: String = "",
    val paneTitle: String = "",
    val tooltip: String = "",
    val platformIndex: Int = -1,
    val children: List<ObserveSnapshot> = emptyList(),
    val isFocused: Boolean = false
) {
    /** A node the user can act on. */
    val isInteractive: Boolean
        get() = isClickable || isCheckable || isEditable || isScrollable

    /** A node that carries readable content. */
    val hasLabel: Boolean
        get() = text.isNotBlank() || contentDescription.isNotBlank()

    /** Own text plus description, de-duplicated in that order. */
    val label: String
        get() = buildList {
            if (text.isNotBlank()) add(text)
            if (contentDescription.isNotBlank() && contentDescription != text) add(contentDescription)
        }.joinToString(" | ")
}
