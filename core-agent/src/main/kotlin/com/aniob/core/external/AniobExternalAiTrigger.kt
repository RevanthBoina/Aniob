package com.aniob.core.external

import com.aniob.core.intent.AniobIntent
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.tools.DevicePowerState
import kotlinx.coroutines.CancellationException

/**
 * Interface for Cloud AI Providers (Omniroute etc.) in core-agent.
 */
interface AniobCloudLlmProvider {
    val name: String
    val isConfigured: Boolean get() = true
    suspend fun generate(prompt: String, systemPrompt: String): String
    suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit): String
}

sealed interface ExternalAiResult {
    val answer: String
    val provider: String
    val intent: AniobIntent
    val latencyMs: Long

    data class Success(
        override val answer: String,
        override val provider: String,
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val fromVault: Boolean = false
    ) : ExternalAiResult

    data class Unavailable(
        override val answer: String,
        override val provider: String = "NO_PROVIDER",
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val reason: String
    ) : ExternalAiResult

    data class Error(
        override val answer: String,
        override val provider: String = "ERROR",
        override val intent: AniobIntent,
        override val latencyMs: Long,
        val cause: String
    ) : ExternalAiResult
}

/**
 * Trigger external AI queries for queries where Aniob should not perform UI automation.
 * Pure JVM, unit-testable.
 */
class AniobExternalAiTrigger(
    private val cloudProvider: AniobCloudLlmProvider?,
    private val localProvider: AniobLocalLlmProvider?,
    private val sharedKnowledgeStore: AniobSharedKnowledgeStore
) {

    suspend fun query(
        prompt: String,
        intent: AniobIntent,
        powerState: DevicePowerState,
        autoMode: String = "auto",
        onDelta: (String) -> Unit = {}
    ): ExternalAiResult {
        val startMs = System.currentTimeMillis()

        // VAULT_QUERY -> check SharedKnowledgeStore first
        if (intent == AniobIntent.VAULT_QUERY) {
            val lower = prompt.lowercase()
            var vaultResult = sharedKnowledgeStore.get(lower)
            if (vaultResult == null) {
                val all = sharedKnowledgeStore.getAll()
                vaultResult = all.entries.firstOrNull { (k, _) ->
                    lower.contains(k) || k.contains(lower)
                }?.value
            }
            if (vaultResult != null) {
                onDelta(vaultResult)
                return ExternalAiResult.Success(
                    answer = vaultResult,
                    provider = "VAULT",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs,
                    fromVault = true
                )
            }
        }

        val basePrompt = when (intent) {
            AniobIntent.KNOWLEDGE_QA -> """
                You are Aniob Knowledge Assistant. Provide a direct, concise, and helpful answer.
                Do NOT generate device automation actions or UI gestures.
            """.trimIndent()
            AniobIntent.WEB_RESEARCH -> """
                You are Aniob Research Assistant. Provide factual, up-to-date information concisely.
                Do NOT generate device automation actions or UI gestures.
            """.trimIndent()
            AniobIntent.VAULT_QUERY -> """
                You are Aniob Personal Memory Assistant. Answer based on known user preferences.
            """.trimIndent()
            AniobIntent.EXTERNAL_AI_QUERY -> """
                You are Aniob, a friendly and intelligent assistant. Provide a helpful response directly.
            """.trimIndent()
            else -> "You are Aniob."
        }
        // An answer-only reply must still never say "I can't use your phone": that line is what a
        // user hears when they ask "can you control my phone?", and it is false.
        val systemPrompt = basePrompt + "\n" + com.aniob.core.capability.AniobCapabilityManifest.IDENTITY_LINE

        val isLocalModeOnly = autoMode.lowercase() == "local-only"
        val isLocalReady = localProvider != null && localProvider.isAvailable()
        val isCloudReady = !isLocalModeOnly && powerState.isNetworkAvailable && cloudProvider != null && cloudProvider.isConfigured

        val useLocal = when (autoMode.lowercase()) {
            "local-only" -> true
            "cloud-only" -> false
            "local-first" -> isLocalReady || !isCloudReady
            "balanced", "auto" -> {
                if (!powerState.isNetworkAvailable) {
                    isLocalReady
                } else if (powerState.batteryPercent < 15 && !powerState.isCharging) {
                    false // low battery -> cloud
                } else if (intent == AniobIntent.KNOWLEDGE_QA && isLocalReady) {
                    true // For KNOWLEDGE_QA simple -> local Phi-4 Mini is smartest 3.8B MMLU 68%
                } else if (isCloudReady) {
                    false // for web research -> cloud
                } else {
                    isLocalReady
                }
            }
            else -> !isCloudReady && isLocalReady
        }

        val result: ExternalAiResult = try {
            if (isLocalModeOnly) {
                if (isLocalReady) {
                    queryLocal(localProvider!!, prompt, intent, startMs, onDelta)
                } else {
                    val reason = "Local-only mode active: local model is missing or not installed"
                    val answer = "⚠️ $reason"
                    onDelta(answer)
                    ExternalAiResult.Unavailable(
                        answer = answer,
                        provider = "NO_PROVIDER",
                        intent = intent,
                        latencyMs = System.currentTimeMillis() - startMs,
                        reason = reason
                    )
                }
            } else if (useLocal && isLocalReady) {
                queryLocal(localProvider!!, prompt, intent, startMs, onDelta)
            } else if (isCloudReady) {
                val fullAnswer = cloudProvider!!.generateStreaming(prompt, systemPrompt) { delta ->
                    onDelta(delta)
                }
                val isProviderError = fullAnswer.startsWith("Omniroute API key is not configured") ||
                    fullAnswer.startsWith("Error from Omniroute Cloud") ||
                    fullAnswer.startsWith("Request failed:")
                if (isProviderError) {
                    if (isLocalReady) {
                        queryLocal(localProvider!!, prompt, intent, startMs, onDelta)
                    } else {
                        ExternalAiResult.Error(
                            answer = fullAnswer,
                            provider = "ERROR",
                            intent = intent,
                            latencyMs = System.currentTimeMillis() - startMs,
                            cause = fullAnswer
                        )
                    }
                } else {
                    ExternalAiResult.Success(
                        answer = fullAnswer,
                        provider = cloudProvider.name,
                        intent = intent,
                        latencyMs = System.currentTimeMillis() - startMs
                    )
                }
            } else if (isLocalReady) {
                queryLocal(localProvider!!, prompt, intent, startMs, onDelta)
            } else {
                val reason = "No AI provider or on-device model is configured to answer this question. Download an on-device model in Models or configure an API key in Settings."
                val answer = "⚠️ $reason"
                onDelta(answer)
                ExternalAiResult.Unavailable(
                    answer = answer,
                    provider = "NO_PROVIDER",
                    intent = intent,
                    latencyMs = System.currentTimeMillis() - startMs,
                    reason = reason
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errMsg = "Failed to query AI provider: ${e.message ?: "Unknown error"}"
            onDelta(errMsg)
            ExternalAiResult.Error(
                answer = errMsg,
                provider = "ERROR",
                intent = intent,
                latencyMs = System.currentTimeMillis() - startMs,
                cause = e.message ?: "Unknown error"
            )
        }

        // Only save preference into vault when successfully answered and user asked to remember
        if (result is ExternalAiResult.Success) {
            if (prompt.contains("remember", ignoreCase = true) || prompt.contains("save my", ignoreCase = true)) {
                sharedKnowledgeStore.put(prompt.lowercase(), result.answer)
            }
        }

        return result
    }
    private fun queryLocal(
        provider: AniobLocalLlmProvider,
        prompt: String,
        intent: AniobIntent,
        startMs: Long,
        onDelta: (String) -> Unit
    ): ExternalAiResult {
        // A JSON-shaped reply may be the provider's failure envelope. Hold it until
        // classified so it never appears briefly as a successful chat bubble.
        val prefix = StringBuilder()
        var jsonShaped: Boolean? = null
        val answer = provider.chatStreaming(prompt) { delta ->
            if (jsonShaped == null) {
                prefix.append(delta)
                val first = prefix.firstOrNull { !it.isWhitespace() }
                if (first != null) {
                    jsonShaped = first == '{'
                    if (jsonShaped == false) { onDelta(prefix.toString()); prefix.clear() }
                }
            } else if (jsonShaped == true) {
                prefix.append(delta)
            } else {
                onDelta(delta)
            }
        }
        val result = localResult(answer, intent, startMs)
        if (result is ExternalAiResult.Success && prefix.isNotEmpty()) onDelta(prefix.toString())
        return result
    }

    private fun localResult(answer: String, intent: AniobIntent, startMs: Long): ExternalAiResult {
        // The local provider uses an action-shaped failure envelope for missing engines.
        // Do not display or remember that envelope as an ordinary answer.
        val failure = runCatching {
            @Suppress("UNCHECKED_CAST")
            com.squareup.moshi.Moshi.Builder().build().adapter(Any::class.java).fromJson(answer) as? Map<String, Any?>
        }.getOrNull()?.takeIf { it["tool"] == "fail" }
        val elapsed = System.currentTimeMillis() - startMs
        return if (failure != null) {
            val reason = failure["reason"] as? String ?: "The on-device model could not generate an answer."
            ExternalAiResult.Error(
                answer = "The on-device model could not answer. Check your model in Models and try again.",
                provider = "LOCAL_SLM", intent = intent, latencyMs = elapsed, cause = reason
            )
        } else {
            ExternalAiResult.Success(answer = answer, provider = "LOCAL_SLM", intent = intent, latencyMs = elapsed)
        }
    }

}
