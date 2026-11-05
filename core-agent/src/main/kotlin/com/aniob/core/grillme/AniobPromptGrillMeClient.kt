package com.aniob.core.grillme

import com.squareup.moshi.Moshi
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects `configs/prompts/grill_me_prompt.txt` to the engine. Until now that prompt file was never read:
 * the ViewModel built `AniobGrillMeEngine()` with no client, so Grill-Me was a keyword table.
 *
 * Contract with the engine: throw when there is no usable answer, and the engine falls back to its
 * deterministic slot ladder. A slow or broken model therefore costs at most [timeoutMs], never the task.
 * [complete] receives (systemPrompt, userMessage) and returns the raw model text.
 */
class AniobPromptGrillMeClient(
    private val systemPrompt: String,
    private val timeoutMs: Long = 2_500L,
    private val maxQuestions: Int = 2,
    private val complete: suspend (system: String, user: String) -> String?
) : GrillMeLlmClient {

    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    override suspend fun generateClarificationQuestions(taskPrompt: String): AniobGrillMeResult {
        val raw = withTimeoutOrNull(timeoutMs) { complete(systemPrompt, "User command: ${taskPrompt.take(500)}") }
            ?: throw IllegalStateException("grill-me model gave no answer within ${timeoutMs}ms")
        return parse(raw) ?: throw IllegalStateException("grill-me model answer was not valid JSON")
    }

    /** Null when [raw] is not the agreed shape. Never trusts the model's ids, option counts or defaults. */
    fun parse(raw: String): AniobGrillMeResult? {
        val json = raw.substringAfter('{', "").let { if (it.isEmpty()) return null else "{$it" }.substringBeforeLast('}') + "}"
        val root = (runCatching { adapter.fromJson(json) }.getOrNull() as? Map<*, *>) ?: return null
        val needs = root["needsClarification"] as? Boolean ?: return null
        if (!needs) return AniobGrillMeResult(needsClarification = false)
        val questions = (root["questions"] as? List<*>).orEmpty().mapIndexedNotNull { i, q ->
            val m = q as? Map<*, *> ?: return@mapIndexedNotNull null
            val text = (m["question"] as? String)?.trim().orEmpty()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val options = (m["options"] as? List<*>).orEmpty().filterIsInstance<String>().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(4)
            val default = (m["defaultOption"] as? String)?.takeIf { it in options }
            GrillQuestion(
                id = (m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "q${i + 1}",
                question = text.take(160),
                options = options,
                allowFreeText = (m["allowFreeText"] as? Boolean) ?: true,
                defaultOption = default
            )
        }.take(maxQuestions)
        return if (questions.isEmpty()) AniobGrillMeResult(needsClarification = false)
        else AniobGrillMeResult(needsClarification = true, questions = questions)
    }
}
