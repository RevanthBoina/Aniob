package com.aniob.core.auth

/**
 * Builds the JSON body for the signed-in account's model service (Responses API shape, streaming).
 *
 * `store` is false and `instructions` is always present: the service rejects a request without
 * them. No output-token limit is sent, because that service does not accept one. Text and an
 * optional screenshot are supported, which is all the agent's own prompt format needs; tool
 * *results* are parsed by [AniobSseParser] but this app never offers server-side tools.
 */
object AniobResponsesRequest {
    fun build(
        model: String,
        instructions: String,
        userText: String,
        screenshotBase64: String? = null,
        reasoningEffort: String? = null
    ): String {
        val content = mutableListOf<Map<String, Any?>>(
            linkedMapOf("type" to "input_text", "text" to userText)
        )
        if (!screenshotBase64.isNullOrBlank()) {
            content += linkedMapOf(
                "type" to "input_image",
                "image_url" to "data:image/jpeg;base64,$screenshotBase64"
            )
        }
        val body = linkedMapOf<String, Any?>(
            "model" to model,
            "instructions" to instructions.ifBlank { "You are a helpful assistant." },
            "input" to listOf(linkedMapOf("type" to "message", "role" to "user", "content" to content)),
            "stream" to true,
            "store" to false
        )
        val isNonReasoning = model.startsWith("gpt-4", ignoreCase = true) ||
            model.startsWith("gpt-3", ignoreCase = true)
        if (!reasoningEffort.isNullOrBlank() && !isNonReasoning) {
            body["reasoning"] = linkedMapOf("effort" to reasoningEffort)
        }
        return AniobAuthJson.toJson(body)
    }
}

/** Reads the `Retry-After` header: delta-seconds, capped. HTTP-dates are ignored. */
object AniobRetryAfter {
    const val MAX_MS = 10 * 60 * 1000L
    const val DEFAULT_MS = 30_000L

    fun millis(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return DEFAULT_MS
        return (seconds * 1000L).coerceIn(1_000L, MAX_MS)
    }
}
