package com.aniob.core.external

/**
 * The cloud call the agent loop makes for one agent turn: system prompt, optional structured
 * conversation history, current observation prompt and optional screenshot in, raw model text out
 * (parsed later by `AniobStructuredOutput`).
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

    /**
     * Continuous agent-interaction overload carrying structured [ChatTurn] history.
     * Providers without a native multi-turn override receive the structured history flattened with
     * the latest observation so prior actions, execution results, and follow-ups stay connected.
     */
    suspend fun getNextActionRawStreaming(
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        screenshotBase64: String? = null,
        maxTokens: Int = 512,
        onFirstToken: () -> Unit = {}
    ): Result<String> = getNextActionRawStreaming(
        systemPrompt = systemPrompt,
        userPrompt = ChatTurn.flatten(history + ChatTurn(ChatTurn.USER, userPrompt)),
        screenshotBase64 = screenshotBase64,
        maxTokens = maxTokens,
        onFirstToken = onFirstToken
    )

    suspend fun getNextActionRaw(
        systemPrompt: String,
        history: List<ChatTurn>,
        userPrompt: String,
        screenshotBase64: String? = null,
        maxTokens: Int = 512
    ): Result<String> = getNextActionRaw(
        systemPrompt = systemPrompt,
        userPrompt = ChatTurn.flatten(history + ChatTurn(ChatTurn.USER, userPrompt)),
        screenshotBase64 = screenshotBase64,
        maxTokens = maxTokens
    )
}
