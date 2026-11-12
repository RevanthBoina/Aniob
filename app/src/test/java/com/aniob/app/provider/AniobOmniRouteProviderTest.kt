package com.aniob.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AniobOmniRouteProviderTest {

    @Test
    fun defaultProviderHasJsonModeDisabled() {
        val p = AniobOmniRouteProvider(apiKey = "k")
        assertFalse(p.isJsonMode())
    }

    @Test
    fun secondMessageIsSentAsSystemUserAssistantUser() {
        val turns = listOf(
            com.aniob.core.external.ChatTurn("user", "My name is Ravi"),
            com.aniob.core.external.ChatTurn("assistant", "Nice to meet you, Ravi."),
            com.aniob.core.external.ChatTurn("user", "What is my name?")
        )
        val messages = AniobOmniRouteProvider(apiKey = "k").buildMessages("SYS", turns, null)
        val sent = (0 until messages.length()).map { messages.getJSONObject(it) }
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.getString("role") })
        assertEquals(
            listOf("SYS", "My name is Ravi", "Nice to meet you, Ravi.", "What is my name?"),
            sent.map { it.getString("content") }
        )
    }

    @Test
    fun messageListKeepsOnlyTheNewestTwelveTurnsAfterTheSystemPrompt() {
        val turns = (1..30).map {
            com.aniob.core.external.ChatTurn(if (it % 2 == 1) "user" else "assistant", "t$it")
        } + com.aniob.core.external.ChatTurn("user", "latest")
        val messages = AniobOmniRouteProvider(apiKey = "k").buildMessages("SYS", turns, null)
        assertEquals(13, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("t20", messages.getJSONObject(1).getString("content"))
        assertEquals("latest", messages.getJSONObject(12).getString("content"))
    }

    @Test
    fun singleTurnWithScreenshotKeepsTheImageOnTheUserMessage() {
        val messages = AniobOmniRouteProvider(apiKey = "k")
            .buildMessages("SYS", listOf(com.aniob.core.external.ChatTurn("user", "look")), "abc")
        assertEquals(2, messages.length())
        val parts = messages.getJSONObject(1).getJSONArray("content")
        assertEquals("look", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,abc", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun withJsonModeReturnsJsonEnforcingInstance() {
        val p = AniobOmniRouteProvider(apiKey = "k")
        val json = p.withJsonMode()
        assertTrue(json.isJsonMode())
        assertFalse(p.isJsonMode())
    }

    @Test
    fun resolveEndpointNormalizesBareHostAndV1Paths() {
        assertEquals(
            "http://localhost:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("")
        )
        assertEquals(
            "http://localhost:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("http://localhost:20128")
        )
        assertEquals(
            "http://localhost:20128/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("http://localhost:20128/v1/")
        )
        assertEquals(
            "https://omniroute.online/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("https://omniroute.online")
        )
        assertEquals(
            "https://omniroute.online/v1/chat/completions",
            AniobProviderConfig.resolveEndpoint("https://omniroute.online/v1/chat/completions")
        )
        assertEquals(
            "https://omniroute.online/v1/models",
            AniobProviderConfig.resolveModelsEndpoint("https://omniroute.online")
        )
    }

    @Test
    fun coerceModelPreservesAutoCombosAndProviderModelsWhileCoercingLegacyBareGpt() {
        assertEquals("auto", AniobProviderConfig.coerceModel(""))
        assertEquals("auto", AniobProviderConfig.coerceModel("gpt-4o"))
        assertEquals("auto", AniobProviderConfig.coerceModel("gpt-4o-mini"))
        assertEquals("auto/fast", AniobProviderConfig.coerceModel("auto/fast"))
        assertEquals("auto/smart", AniobProviderConfig.coerceModel("auto/smart"))
        assertEquals("auto/vision", AniobProviderConfig.coerceModel("auto/vision"))
        assertEquals("auto/coding", AniobProviderConfig.coerceModel("auto/coding"))
        assertEquals("auto/cheap", AniobProviderConfig.coerceModel("auto/cheap"))
        assertEquals("kimi/kimi-k3", AniobProviderConfig.coerceModel("kimi/kimi-k3"))
        assertEquals("openai/gpt-4o", AniobProviderConfig.coerceModel("openai/gpt-4o"))
    }

    @Test
    fun extractAssistantContentSupportsStringArrayAndReasoningContent() {
        val standardJson = """{"choices":[{"message":{"content":"{\"action\":\"finish\",\"summary\":\"done\"}"}}]}"""
        assertEquals(
            """{"action":"finish","summary":"done"}""",
            AniobOmniRouteProvider.extractAssistantContent(standardJson)
        )

        val arrayJson = """{"choices":[{"message":{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"OmniRoute"}]}}]}"""
        assertEquals("Hello OmniRoute", AniobOmniRouteProvider.extractAssistantContent(arrayJson))

        val reasoningJson = """{"choices":[{"message":{"content":"","reasoning_content":"{\"action\":\"wait\"}"}}]}"""
        assertEquals("""{"action":"wait"}""", AniobOmniRouteProvider.extractAssistantContent(reasoningJson))

        val sseStream = """
            data: {"choices":[{"delta":{"role":"assistant"}}]}
            
            data: {"choices":[{"delta":{"content":"{\"tool\":\"OPEN_APP\",\"package_name\":\"com.android.chrome\"}"}}]}
            
            data: [DONE]
        """.trimIndent()
        assertEquals(
            """{"tool":"OPEN_APP","package_name":"com.android.chrome"}""",
            AniobOmniRouteProvider.extractAssistantContent(sseStream)
        )
    }
}