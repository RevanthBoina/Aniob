package com.aniob.core.providers

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

/**
 * Parsed shape of the JSON tool call an on-device SLM is expected to emit.
 *
 * v2: the element is named by a [SemanticTarget]; node ids are no longer expressible.
 */
data class AniobToolCall(
    val thought: String,
    val tool: String,
    val target: SemanticTarget? = null,
    val text: String? = null,
    val packageName: String? = null,
    val direction: SwipeDirection? = null,
    val key: KeyType? = null,
    val summary: String? = null,
    val reason: String? = null
)

/**
 * Outcome of a local generation attempt.
 *
 * The critical contract: only [Ready] carries model output. Every other variant means
 * "no model produced this" so callers must surface an explicit, user-actionable failure
 * instead of executing a fabricated action against a node id that may not exist.
 */
sealed class AniobGenerationResult {
    data class Ready(val fullText: String) : AniobGenerationResult()

    /** Neither the native library nor the model file is usable on this device. */
    data object ModelUnavailable : AniobGenerationResult()

    /** Native library/model present but generation failed or timed out (>15s). */
    data class GenerationFailed(val reason: String) : AniobGenerationResult()

    /**
     * Model ran, but the emitted text is not a parseable action tool call. Raw text is
     * retained for the caller's diagnostics but must not be executed as an action.
     */
    data class Unparseable(val rawText: String) : AniobGenerationResult()
}

/**
 * Thin adapter over the single canonical parser.
 *
 * v2 note: this object performs NO JSON parsing of its own. It delegates to
 * [AniobActionSchema.parseActionJson] and only validates SoM targets against the live screen,
 * so the "hallucinated node id" guarantee survives the action-model rewrite.
 */
object AniobToolCallParser {

    /** Parses raw model text into the canonical action, or null when it is not a valid action. */
    fun parseToAction(rawText: String): AniobAction? = AniobActionSchema.parseActionJsonOrNull(rawText)

    /**
     * Maps raw model text onto an action, failing explicitly when an SoM index is not present
     * on [screen]. Semantic targets (text/resource-id/desc) defer to the grounding resolver.
     */
    fun toAction(rawText: String, screen: com.aniob.core.domain.AniobScreenState?): AniobAction {
        val action = parseToAction(rawText)
            ?: return AniobAction.Fail(reason = "Local model output was not a valid action", thought = "unparseable")
        return validateAgainstScreen(action, screen)
    }

    private fun validateAgainstScreen(
        action: AniobAction,
        screen: com.aniob.core.domain.AniobScreenState?
    ): AniobAction {
        if (screen == null) return action
        val validIds = screen.nodes.map { it.id }.toSet()
        fun check(target: SemanticTarget, failReason: String): AniobAction? {
            if (target is SemanticTarget.SomIndex && target.index == com.aniob.core.domain.ActionBurst.UNNAMED_TARGET) {
                // The one intentional non-element target: an input without a named field. It is
                // valid only when the live screen has exactly one obvious field to type into.
                if (action is AniobAction.InputText) {
                    val field = com.aniob.core.perception.ActionTargetResolver.soleEditableField(screen)
                    return if (field != null && !field.isPassword) {
                        null // accepted: the executor resolves it against the same rule
                    } else {
                        AniobAction.Fail(
                            reason = "$failReason: the screen does not have exactly one usable editable field",
                            thought = "unnamed input needs a single editable field"
                        )
                    }
                }
                return AniobAction.Fail(
                    reason = "$failReason on missing SoM index ${target.index}",
                    thought = "hallucinated target"
                )
            }
            if (target is SemanticTarget.SomIndex && target.index !in validIds) {
                return AniobAction.Fail(
                    reason = "$failReason on missing SoM index ${target.index}",
                    thought = "hallucinated target"
                )
            }
            return null
        }
        return when (action) {
            is AniobAction.Tap -> check(action.target, "Local model proposed tap") ?: action
            is AniobAction.LongPress -> check(action.target, "Local model proposed long_press") ?: action
            is AniobAction.InputText -> check(action.target, "Local model proposed input_text") ?: action
            else -> action
        }
    }
}