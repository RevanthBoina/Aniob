package com.aniob.core.tools

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

data class ScrollResult(
    val found: Boolean,
    val node: AniobNode?,
    val swipes: Int,
    /** The capture the target was found on (or the last one read). Null when nothing was captured. */
    val screen: AniobScreenState? = null,
    /** True when the search hit the end of the container and tried the other direction. */
    val reversed: Boolean = false
)

/**
 * Scroll-until-visible inside one specific container.
 *
 * A target that is not on screen is usually further along a list or a picker. Rather than report
 * "not found" (or scroll the whole window), this scrolls the container that most plausibly holds
 * the target in small steps, re-reads the screen after each one, and stops as soon as the target
 * appears. When the visible content stops changing the end has been reached: the search turns
 * round once, and fails honestly if the other end is reached too.
 *
 * Pure JVM; the platform scroll and the capture are supplied by the caller.
 */
object AniobScrollHelper {
    /** Hard ceiling on scroll steps for one search, both directions together. */
    const val MAX_SWIPES = 24

    /** Consecutive steps with unchanged content that mean "this is the end of the container". */
    const val STALL_LIMIT = 3

    /** Step for picker-sized containers: about one row, so a value is never skipped. */
    const val PICKER_STEP_PX = 140

    /** Step for lists, as a fraction of the container height: most of a page, with overlap. */
    const val LIST_STEP_FRACTION = 0.6

    /** A container at most this tall is treated as a picker (wheel, short carousel, dropdown). */
    const val PICKER_MAX_HEIGHT_PX = 600

    private const val MIN_CONTAINER_AREA = 1000
    private const val MIN_HINT_TOKEN_CHARS = 3

    fun findScrollableContainers(screen: AniobScreenState): List<AniobNode> {
        return screen.nodes.filter { it.isScrollable && it.bounds.area > MIN_CONTAINER_AREA }
            .sortedByDescending { it.bounds.area }
    }

    fun getSemanticDirection(taskPrompt: String): SwipeDirection {
        val lower = taskPrompt.lowercase()
        return when {
            lower.contains("up") || lower.contains("top") || lower.contains("previous") -> SwipeDirection.UP
            else -> SwipeDirection.DOWN // Default down to reveal content below
        }
    }

    /** The text a target is looked up by, or null when the target is not textual. */
    fun textOf(target: SemanticTarget?): String? = when (target) {
        is SemanticTarget.Text -> target.text
        is SemanticTarget.ContentDesc -> target.desc
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** Whether [node] is what a textual [target] refers to. */
    fun matches(node: AniobNode, target: SemanticTarget?): Boolean = when (target) {
        is SemanticTarget.Text ->
            if (target.exact) node.text.trim().equals(target.text.trim(), ignoreCase = true)
            else target.text.isNotBlank() && node.text.contains(target.text.trim(), ignoreCase = true)
        is SemanticTarget.ContentDesc ->
            if (target.exact) node.contentDescription.trim().equals(target.desc.trim(), ignoreCase = true)
            else target.desc.isNotBlank() && node.contentDescription.contains(target.desc.trim(), ignoreCase = true)
        else -> false
    }

    /**
     * The container to scroll for [targetHint]: a scrollable whose own label or visible contents
     * mention a word of the hint, otherwise the smallest scrollable on screen (a picker or an
     * inner list is a more specific place to look than the whole window). Null when nothing scrolls.
     *
     * The node list is flat, so "contents" means nodes whose centre lies inside the container.
     */
    fun chooseContainer(screen: AniobScreenState, targetHint: String = ""): AniobNode? {
        val candidates = screen.nodes.filter { it.isScrollable && it.isEnabled && it.bounds.area > MIN_CONTAINER_AREA }
        if (candidates.isEmpty()) return null
        val tokens = targetHint.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_HINT_TOKEN_CHARS }
        if (tokens.isNotEmpty()) {
            val mentioning = candidates.filter { container ->
                val labels = sequenceOf(container.text, container.contentDescription, container.viewId) +
                    contentsOf(screen, container).flatMap { sequenceOf(it.text, it.contentDescription) }
                labels.any { label -> tokens.any { label.contains(it, ignoreCase = true) } }
            }
            mentioning.minByOrNull { it.bounds.area }?.let { return it }
        }
        return candidates.minByOrNull { it.bounds.area }
    }

    fun isPickerSized(container: AniobNode): Boolean =
        container.height <= PICKER_MAX_HEIGHT_PX || container.className.contains("Picker", ignoreCase = true)

    /** Pixels to move per step in [container]. */
    fun stepPx(container: AniobNode): Int =
        if (isPickerSized(container)) PICKER_STEP_PX
        else (container.height * LIST_STEP_FRACTION).toInt().coerceAtLeast(PICKER_STEP_PX)

    /** Fingerprint of what is visible inside [container]: the (text, contentDescription) pairs. */
    fun visibleHash(screen: AniobScreenState, container: AniobNode): Int =
        contentsOf(screen, container).map { it.text to it.contentDescription }.toList().hashCode()

    private fun contentsOf(screen: AniobScreenState, container: AniobNode): Sequence<AniobNode> =
        screen.nodes.asSequence().filter {
            it.id != container.id && it.bounds != container.bounds &&
                container.bounds.contains(it.centerX, it.centerY)
        }

    /**
     * Scrolls [container] until a node satisfying [targetPredicate] is on screen.
     *
     * @param capture reads the screen after a step has settled (settling is the caller's job).
     * @param scrollNode scrolls the container by about `dyPx`; `forward` reveals later content.
     *   Returning false means the platform refused, which counts as "did not move".
     * @param startForward first direction to try.
     */
    suspend fun scrollUntilFound(
        screen: AniobScreenState,
        targetPredicate: (AniobNode) -> Boolean,
        container: AniobNode?,
        capture: suspend () -> AniobScreenState,
        scrollNode: suspend (node: AniobNode, forward: Boolean, dyPx: Int) -> Boolean,
        startForward: Boolean = true
    ): ScrollResult {
        screen.nodes.firstOrNull(targetPredicate)?.let { return ScrollResult(true, it, 0, screen) }
        if (container == null) return ScrollResult(false, null, 0, screen)

        val step = stepPx(container)
        var current = screen
        var lastHash = visibleHash(current, container)
        var forward = startForward
        var reversed = false
        var stalled = 0
        var swipes = 0

        while (swipes < MAX_SWIPES) {
            val moved = scrollNode(container, forward, step)
            swipes++
            if (moved) {
                current = capture()
                current.nodes.firstOrNull(targetPredicate)?.let {
                    return ScrollResult(true, it, swipes, current, reversed)
                }
            }
            val hash = visibleHash(current, container)
            if (moved && hash != lastHash) {
                stalled = 0
                lastHash = hash
                continue
            }
            stalled++
            if (stalled >= STALL_LIMIT) {
                if (reversed) break
                reversed = true
                forward = !forward
                stalled = 0
            }
        }
        return ScrollResult(false, null, swipes, current, reversed)
    }
}
