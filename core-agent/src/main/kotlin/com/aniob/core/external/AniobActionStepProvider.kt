package com.aniob.core.external

/**
 * The cloud call the agent loop makes for one UI-action step: system prompt, observation and an
 * optional screenshot in, raw model text out (parsed later by `AniobStructuredOutput`).
 *
 * Extracted from `AniobOmniRouteProvider` so the step loop can use any cloud source. A failure is
 * a [Result.failure]; the caller degrades the step with `AniobGatewayClassifier.kindOf(error)`.
 */
interface AniobActionStepProvider {
    suspend fun getNextActionRawStreaming(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null,
        maxTokens: Int = 512,
        onFirstToken: () -> Unit = {}
    ): Result<String>

    suspend fun getNextActionRaw(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String? = null,
        maxTokens: Int = 512
    ): Result<String>
}
