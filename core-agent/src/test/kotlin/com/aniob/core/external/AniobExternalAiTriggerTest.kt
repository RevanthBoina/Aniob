package com.aniob.core.external

import com.aniob.core.intent.AniobIntent
import com.aniob.core.memory.AniobSharedKnowledgeStore
import com.aniob.core.providers.AniobLocalLlmProvider
import com.aniob.core.tools.DevicePowerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Records the exact message list a provider with a native message list would send. */
    private class RecordingCloudProvider : AniobCloudLlmProvider {
        override val name = "RECORDING_CLOUD"
        val requests = mutableListOf<List<Pair<String, String>>>()
        var reply = ""

        override suspend fun generate(prompt: String, systemPrompt: String): String = reply
        override suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit): String =
            error("the multi-turn overload must be used")

        override suspend fun generateStreaming(
            turns: List<ChatTurn>,
            systemPrompt: String,
            onDelta: (String) -> Unit
        ): String {
            requests += listOf("system" to systemPrompt) + turns.map { it.role to it.content }
            return reply
        }
    }

    @Test
    fun `second message carries the first exchange to the cloud provider`() = runBlocking {
        val cloud = RecordingCloudProvider()
        val trigger = AniobExternalAiTrigger(cloud, null, FakeKnowledgeStore())
        val history = mutableListOf<ChatTurn>()

        cloud.reply = "Nice to meet you, Ravi."
        val first = trigger.query(
            prompt = "My name is Ravi", intent = AniobIntent.EXTERNAL_AI_QUERY,
            powerState = defaultPower, autoMode = "cloud-only", history = history.toList()
        )
        history += ChatTurn(ChatTurn.USER, "My name is Ravi")
        history += ChatTurn(ChatTurn.ASSISTANT, first.answer)

        cloud.reply = "Ravi."
        trigger.query(
            prompt = "What is my name?", intent = AniobIntent.EXTERNAL_AI_QUERY,
            powerState = defaultPower, autoMode = "cloud-only", history = history.toList()
        )

        assertEquals(listOf("system", "user"), cloud.requests[0].map { it.first })
        val second = cloud.requests[1]
        assertEquals(listOf("system", "user", "assistant", "user"), second.map { it.first })
        assertEquals(
            listOf("My name is Ravi", "Nice to meet you, Ravi.", "What is my name?"),
            second.drop(1).map { it.second }
        )
    }

    @Test
    fun `on-device model receives the earlier turns in its single prompt`() = runBlocking {
        val seen = mutableListOf<String>()
        val local = object : AniobLocalLlmProvider {
            override fun chatStreaming(prompt: String, onDelta: (String) -> Unit): String {
                seen += prompt
                return "Ravi."
            }
            override fun isAvailable() = true
            override fun release() {}
        }
        val trigger = AniobExternalAiTrigger(null, local, FakeKnowledgeStore())

        trigger.query("hello", AniobIntent.EXTERNAL_AI_QUERY, defaultPower, "local-only")
        trigger.query(
            prompt = "What is my name?", intent = AniobIntent.EXTERNAL_AI_QUERY,
            powerState = defaultPower, autoMode = "local-only",
            history = listOf(ChatTurn(ChatTurn.USER, "My name is Ravi"), ChatTurn(ChatTurn.ASSISTANT, "Hi Ravi."))
        )

        assertEquals("hello", seen[0])
        assertEquals(
            "Conversation so far:\nUser: My name is Ravi\nAssistant: Hi Ravi.\n\nUser: What is my name?",
            seen[1]
        )
    }

    @Test
    fun `flatten keeps only the newest turns`() {
        val turns = (1..20).map { ChatTurn(if (it % 2 == 1) ChatTurn.USER else ChatTurn.ASSISTANT, "t$it") }
        val flat = ChatTurn.flatten(turns)
        assertFalse(flat.contains("t8"))
        assertTrue(flat.contains("User: t9"))
        assertTrue(flat.endsWith("User: t20"))
    }
}
