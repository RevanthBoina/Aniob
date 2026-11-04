package com.aniob.core.external

import com.aniob.core.intent.AniobIntent
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.tools.DevicePowerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobExternalAiTriggerTest {

    private class FakeLocalProvider(
        var available: Boolean = true,
        var reply: String = "Local response"
    ) : AniobLocalLlmProvider {
        var chatCalled = false

        override fun isAvailable(): Boolean = available

        override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String {
            chatCalled = true
            onDelta(reply)
            return reply
        }

        override fun release() {}
    }

    private class FakeCloudProvider(
        override val name: String = "FAKE_CLOUD",
        override val isConfigured: Boolean = true,
        var reply: String = "Cloud response"
    ) : AniobCloudLlmProvider {
        var generateCalled = false

        override suspend fun generate(prompt: String, systemPrompt: String): String = reply

        override suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit): String {
            generateCalled = true
            onDelta(reply)
            return reply
        }
    }

    private class FakeKnowledgeStore : AniobSharedKnowledgeStore {
        private val data = mutableMapOf<String, String>()

        override fun get(key: String): String? = data[key]

        override fun put(key: String, value: String) {
            data[key] = value
        }

        override fun getAll(): Map<String, String> = data.toMap()

        override fun remove(key: String) {
            data.remove(key)
        }

        override fun clear() {
            data.clear()
        }
    }

    private val defaultPower = DevicePowerState(
        isNetworkAvailable = true,
        isCharging = true,
        batteryPercent = 80,
        isThermalThrottled = false
    )

    @Test
    fun `local failure envelope is not streamed or saved as an answer`() = runBlocking {
        val local = FakeLocalProvider(reply = """{"tool":"fail","reason":"engine unavailable"}""")
        val knowledge = FakeKnowledgeStore()
        val deltas = mutableListOf<String>()
        val result = AniobExternalAiTrigger(null, local, knowledge).query(
            prompt = "remember my preference", intent = AniobIntent.KNOWLEDGE_QA,
            powerState = defaultPower, autoMode = "local-only", onDelta = { deltas.add(it) }
        )
        assertTrue(result is ExternalAiResult.Error)
        assertTrue(deltas.isEmpty())
        assertTrue(knowledge.getAll().isEmpty())
    }

    @Test
    fun testLocalFallbackWhenCloudUnconfigured() = runBlocking {
        val local = FakeLocalProvider(available = true, reply = "Paris is the capital")
        val cloudUnconfigured = FakeCloudProvider(isConfigured = false)
        val knowledge = FakeKnowledgeStore()

        val trigger = AniobExternalAiTrigger(
            cloudProvider = cloudUnconfigured,
            localProvider = local,
            sharedKnowledgeStore = knowledge
        )

        val result = trigger.query(
            prompt = "What is the capital of France?",
            intent = AniobIntent.KNOWLEDGE_QA,
            powerState = defaultPower,
            autoMode = "auto"
        )

        assertTrue(result is ExternalAiResult.Success)
        assertEquals("LOCAL_SLM", result.provider)
        assertEquals("Paris is the capital", result.answer)
        assertTrue(local.chatCalled)
    }

    @Test
    fun testLocalOnlyModeHonorsLocalProvider() = runBlocking {
        val local = FakeLocalProvider(available = true, reply = "Offline answer")
        val cloud = FakeCloudProvider(isConfigured = true)
        val knowledge = FakeKnowledgeStore()

        val trigger = AniobExternalAiTrigger(
            cloudProvider = cloud,
            localProvider = local,
            sharedKnowledgeStore = knowledge
        )

        val result = trigger.query(
            prompt = "What is 2 + 2?",
            intent = AniobIntent.KNOWLEDGE_QA,
            powerState = defaultPower,
            autoMode = "local-only"
        )

        assertTrue(result is ExternalAiResult.Success)
        assertEquals("LOCAL_SLM", result.provider)
        assertTrue(local.chatCalled)
    }

    @Test
    fun testVaultQueryBypassesModel() = runBlocking {
        val local = FakeLocalProvider(available = true)
        val cloud = FakeCloudProvider(isConfigured = true)
        val knowledge = FakeKnowledgeStore().apply {
            put("my name", "Alice")
        }

        val trigger = AniobExternalAiTrigger(
            cloudProvider = cloud,
            localProvider = local,
            sharedKnowledgeStore = knowledge
        )

        val result = trigger.query(
            prompt = "my name",
            intent = AniobIntent.VAULT_QUERY,
            powerState = defaultPower,
            autoMode = "auto"
        )

        assertTrue(result is ExternalAiResult.Success)
        assertEquals("VAULT", result.provider)
        assertEquals("Alice", result.answer)
        // Ensure no model was called
        org.junit.Assert.assertFalse(local.chatCalled)
        org.junit.Assert.assertFalse(cloud.generateCalled)
    }
}
