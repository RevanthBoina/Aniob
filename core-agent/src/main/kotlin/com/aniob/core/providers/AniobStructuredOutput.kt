package com.aniob.core.providers

import com.aniob.core.config.AniobDecodeConfig
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.ActionBurst
import com.aniob.core.domain.AniobActionSchema
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.perception.BurstPlanScope

/**
 * The one structured-output boundary for every provider (finding #3).
 *
 * Cloud, local SLM, mock and skill replay all return *data*; parsing lives here, and only here,
 * so three ad-hoc parsers can no longer drift. Flow:
 *
 * ```
 * raw --parse--> action?  --miss--> one repair retry with the schema error fed back
 *                          --miss--> deterministic fallback (never a guessed tap)
 * ```
 *
 * The fallback for executor-role output is a bounded `Wait`, not a fabricated tap: a model that
 * cannot emit the vocabulary must not move the UI. Callers can distinguish a repaired parse from
 * a fallback via [StructuredParse.usedFallback].
 *
 * The bundled-plan path ([parseBurstOrRepair]) is deliberately *not* fail-closed the way a single
 * action is. A plan is usually correct and merely spans more than one screen; the part of it that
 * this screen can ground is returned and the rest is deferred. Only a reply the parser cannot read
 * at all, or one whose first action names an element that is not on the screen, is refused — and
 * even then it is refused as `null` so the caller's single-action path stays available, instead of
 * being replaced by a `Wait` that used to cost a whole extra round trip per step.
 */
object AniobStructuredOutput {

    /**
     * Parses a bundled plan and reduces it to the actions the observed screen can execute.
     *
     * @param goal the user's request, used to decide whether typing into an unnamed field is
     *   trustworthy (an injected screen must not be able to push arbitrary text into the UI).
     * @return null when the reply is not a bundled plan, or when nothing in it can be grounded on
     *   [screenState]; a non-null result always has at least one executable action, or is an
     *   explicit `is_complete` with no actions left to run.
     */
    fun parseBurstOrRepair(raw: String, screenState: AniobScreenState, goal: String = ""): ActionBurst? {
        if (!PLAN_ARRAY.containsMatchIn(raw)) return null
        val parsed = AniobActionSchema.parseActionBurstOrNull(raw) ?: return null

        // Completion claimed with an empty action list: nothing to ground, nothing to execute.
        if (parsed.planned.isEmpty()) return parsed.takeIf { it.isComplete }

        val scoped = BurstPlanScope.scope(parsed, screenState, goal)
        if (!scoped.groundedOnScreen) return null
        return scoped.actionable
    }

    /** Outcome of the parse-or-repair boundary. */
    data class StructuredParse(
        val action: AniobAction,
        val repaired: Boolean,
        val usedFallback: Boolean,
        val reason: String
    )

    /**
     * @param screenState live screen used to reject hallucinated SoM indices.
     * @param decode per-role generation params; the fallback and prompt budget derive from it.
     * @param repairCall given a repair prompt (schema + parse error + original text), returns a
     *   fresh completion or null when the provider declines / is unavailable.
     */
    fun parseOrRepair(
        raw: String,
        screenState: AniobScreenState?,
        decode: AniobDecodeConfig = AniobDecodeConfig.forRole(AniobDecodeConfig.Role.EXECUTOR),
        repairCall: (String) -> String? = { null }
    ): StructuredParse {
        firstAttempt(raw, screenState)?.let { return it }

        val error = describeError(raw)
        val repairedRaw = runCatching { repairCall(repairPrompt(raw, error)) }.getOrNull()
        if (repairedRaw != null) {
            firstAttempt(repairedRaw, screenState)?.let {
                return it.copy(repaired = true, reason = "repaired: ${it.reason}")
            }
        }

        return StructuredParse(
            action = fallbackFor(decode.role),
            repaired = true,
            usedFallback = true,
            reason = "unparseable after repair ($error) - using deterministic fallback"
        )
    }

    /** Returns a parse result, or null when [raw] is not a valid action (repair not yet tried). */
    private fun firstAttempt(raw: String, screenState: AniobScreenState?): StructuredParse? {
        val parsed = AniobToolCallParser.parseToAction(raw) ?: return null
        // A model-emitted, schema-valid FAIL is legitimate output, not a parse failure.
        if (parsed is AniobAction.Fail && parsed.reason.isBlank()) return null
        val validated = AniobToolCallParser.toAction(raw, screenState)
        return StructuredParse(validated, repaired = false, usedFallback = false, reason = "parsed")
    }

    /** Builds the repair prompt so suspend-aware callers can fetch the retry themselves. */
    fun repairPrompt(raw: String): String = repairPrompt(raw, describeError(raw))

    /** Builds the repair prompt for a bundled plan that could not be grounded. */
    fun burstRepairPrompt(raw: String, reason: String): String = buildString {
        appendLine("Your previous reply was a multi-action plan, not a single action, and its first")
        appendLine("action does not name an element that exists on the screen.")
        appendLine("ERROR: $reason")
        appendLine("Return ONE JSON object for the NEXT action only:")
        appendLine(AniobActionSchema.ACTION_JSON_SCHEMA)
        appendLine("PREVIOUS OUTPUT:")
        appendLine(raw.take(600))
        append("Use only ids present in the element list. Do not include prose or coordinates.")
    }

    private fun describeError(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "empty response"
        // A refusal ("I can't operate your phone") gets a correction, not a schema lecture.
        if (com.aniob.core.capability.AniobRefusalGuard.isRefusal(trimmed)) {
            return "refused the task. " + com.aniob.core.capability.AniobCapabilityManifest.REFUSAL_CORRECTION
        }
        if (PLAN_ARRAY.containsMatchIn(trimmed)) {
            return "returned a multi-action plan where one action was required"
        }
        val coordField = COORDINATE_HINTS.firstOrNull { Regex("\"$it\"\\s*:").containsMatchIn(trimmed) }
        if (coordField != null) return "coordinate field '$coordField' is not part of the vocabulary"
        return "no valid tool object found"
    }

    private fun repairPrompt(raw: String, error: String): String = buildString {
        appendLine("Your previous output could not be parsed into an action.")
        appendLine("ERROR: $error")
        appendLine("SCHEMA:")
        appendLine(AniobActionSchema.ACTION_JSON_SCHEMA)
        appendLine("PREVIOUS OUTPUT:")
        appendLine(raw.take(600))
        append("Return ONE JSON object matching the schema. Do not include prose or coordinates.")
    }

    /**
     * Deterministic fallback per role. Executor waits (never taps blind); a planner keeps its
     * prior summary and a reflector retries an alternative, both represented as a bounded Wait.
     */
    private fun fallbackFor(role: AniobDecodeConfig.Role): AniobAction = when (role) {
        AniobDecodeConfig.Role.EXECUTOR,
        AniobDecodeConfig.Role.PLANNER,
        AniobDecodeConfig.Role.REFLECTOR,
        AniobDecodeConfig.Role.GRILL,
        AniobDecodeConfig.Role.EXPLORER -> AniobAction.Wait(durationMs = 1000)

        // Plan-5.6 advisory roles never drive the device. An unparseable answer from one of them
        // is a fact about the answer, not a reason to touch the screen, so it fails loudly instead
        // of degrading into a Wait that would look like progress.
        AniobDecodeConfig.Role.VERIFIER,
        AniobDecodeConfig.Role.CONSISTENCY,
        AniobDecodeConfig.Role.SUMMARIZER,
        AniobDecodeConfig.Role.EXTRACTOR ->
            AniobAction.Fail("${role.name.lowercase()} returned an unparseable response")
    }

    private val COORDINATE_HINTS = listOf("x", "y", "startx", "endx", "bounds", "target_node_id")

    private val PLAN_ARRAY = Regex("\"(?:actions|steps|plan)\"\\s*:\\s*\\[")

    /** True when [raw] claims to be a bundled plan; used to pick the plan repair wording. */
    fun looksLikePlan(raw: String): Boolean = PLAN_ARRAY.containsMatchIn(raw)
}
