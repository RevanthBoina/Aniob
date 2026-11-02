package com.aniob.core.execution

import com.aniob.core.domain.SemanticTarget

/**
 * A resolved, executable instruction — pixels included.
 *
 * `DispatchCommand` is the ONLY type in the system that carries absolute coordinates. It is
 * produced exclusively by `AniobActionExecutor.planDispatch` from a *live* screen capture, so
 * a coordinate can never originate from model output.
 */
sealed interface DispatchCommand {
    val targetDescription: String

    /**
     * [nodeId] is the prompt id the target was grounded to. A compact observation keeps a live
     * node handle for it, so the platform layer can act on the node directly and use the
     * coordinates only as the gesture fallback.
     */
    data class TapAt(
        val x: Int,
        val y: Int,
        override val targetDescription: String,
        val nodeId: Int? = null
    ) : DispatchCommand

    data class LongPressAt(
        val x: Int,
        val y: Int,
        val durationMs: Long,
        override val targetDescription: String,
        val nodeId: Int? = null
    ) : DispatchCommand

    data class SetText(
        val nodeId: Int,
        val text: String,
        val clearFirst: Boolean = false,
        /** Press the IME action once the text is committed (search boxes, dialer). */
        val submit: Boolean = false,
        override val targetDescription: String
    ) : DispatchCommand

    data class SwipeGesture(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L,
        override val targetDescription: String
    ) : DispatchCommand

    /**
     * Non-gesture intents: open app, system keys, clipboard, screen reads, finish/fail.
     *
     * [component] is the launch activity's class name (no package prefix) for `OPEN_APP` when the
     * registry knows it. The platform layer then dispatches an **explicit** intent, which cannot be
     * answered by a chooser or shadowed by another app registering the same implicit action.
     */
    data class SystemIntent(
        val name: String,
        val argument: String? = null,
        override val targetDescription: String = name,
        val component: String? = null
    ) : DispatchCommand

    /** Nothing to execute — e.g. a grounding miss. Never a guessed tap. */
    data class NoOp(val reason: String) : DispatchCommand {
        override val targetDescription: String = "noop:$reason"
    }

    companion object {
        fun groundingMiss(target: SemanticTarget): NoOp = NoOp("grounding_miss:${target.describe()}")
    }
}