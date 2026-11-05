package com.aniob.app.model

import com.aniob.app.AniobApplication
import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.providers.AniobGenerationResult
import com.aniob.core.providers.AniobLocalActionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class AniobLlamaCppEngineDriverTest {

    @Test
    fun gemmaChatTemplateFormatsCorrectly() {
        val driver = AniobLlamaCppEngineDriver(
            modelArchitecture = AniobLlamaCppEngineDriver.ModelArchitecture.GEMMA_4
        )
        val formatted = driver.formatPromptForArchitecture(
            prompt = "Open Display settings",
            arch = AniobLlamaCppEngineDriver.ModelArchitecture.GEMMA_4
        )
        assertTrue(formatted.startsWith("<start_of_turn>user\n"))
        assertTrue(formatted.contains("Open Display settings"))
        assertTrue(formatted.endsWith("<end_of_turn>\n<start_of_turn>model\n"))
    }

    @Test
    fun qwenChatTemplateFormatsCorrectly() {
        val driver = AniobLlamaCppEngineDriver(
            modelArchitecture = AniobLlamaCppEngineDriver.ModelArchitecture.QWEN_3_5
        )
        val formatted = driver.formatPromptForArchitecture(
            prompt = "Open Display settings",
            arch = AniobLlamaCppEngineDriver.ModelArchitecture.QWEN_3_5
        )
        assertTrue(formatted.startsWith("<|im_start|>user\n"))
        assertTrue(formatted.contains("Open Display settings"))
        assertTrue(formatted.endsWith("<|im_end|>\n<|im_start|>assistant\n"))
    }

    @Test
    fun eosTokensAreCleanedProperly() {
        val driver = AniobLlamaCppEngineDriver()
        val gemmaRaw = "{\"action\": \"tap\", \"target\": {\"som_index\": 6}}<end_of_turn>"
        val cleanedGemma = driver.cleanModelOutput(gemmaRaw, AniobLlamaCppEngineDriver.ModelArchitecture.GEMMA_4)
        assertEquals("{\"action\": \"tap\", \"target\": {\"som_index\": 6}}", cleanedGemma)

        val qwenRaw = "{\"action\": \"finish\", \"summary\": \"done\"}<|im_end|>"
        val cleanedQwen = driver.cleanModelOutput(qwenRaw, AniobLlamaCppEngineDriver.ModelArchitecture.QWEN_3_5)
        assertEquals("{\"action\": \"finish\", \"summary\": \"done\"}", cleanedQwen)
    }

    @Test
    fun singleHeavyweightEngineMutexPreventsCoexistence() {
        val llamaDriver = AniobLlamaCppEngineDriver(
            simulatedGenerator = { "{\"action\": \"wait\"}" }
        )

        // Load llama.cpp engine
        llamaDriver.load("/tmp/fake_gemma.gguf")
        assertTrue(llamaDriver.isReady())
        assertEquals("llama.cpp GGUF (/tmp/fake_gemma.gguf)", OnDeviceEngineLock.getActiveEngineName())

        // Releasing llama.cpp clears the lock
        llamaDriver.release()
        assertFalse(llamaDriver.isReady())
        assertEquals("NONE", OnDeviceEngineLock.getActiveEngineName())
    }

    @Test
    fun structuredActionParsesCorrectlyFromGeneration() {
        val screen = AniobScreenState(
            packageName = "com.android.settings",
            nodes = listOf(
                AniobNode(id = 6, className = "android.widget.TextView", text = "Battery", bounds = AniobRect(0, 0, 100, 50), isClickable = true)
            )
        )
        val driver = AniobLlamaCppEngineDriver(
            simulatedGenerator = { "{\"tool\": \"TAP\", \"target\": {\"kind\": \"som_index\", \"value\": \"6\"}}" }
        )
        driver.load("/tmp/fake_model.gguf")

        val generated = driver.generate("Find battery")
        assertNotNull(generated)

        val action = AniobLocalActionResolver.resolve(
            generation = AniobGenerationResult.Ready(generated!!),
            screenState = screen
        )

        assertTrue("Action must resolve to Tap", action is AniobAction.Tap)
        val tap = action as AniobAction.Tap
        val target = tap.target as SemanticTarget.SomIndex
        assertEquals(6, target.index)
    }

    @Test
    fun cancellationInvalidatesInFlightGeneration() {
        var driver: AniobLlamaCppEngineDriver? = null
        driver = AniobLlamaCppEngineDriver(
            simulatedGenerator = {
                driver?.cancel()
                "{\"tool\": \"WAIT\"}"
            }
        )
        driver.load("/tmp/fake_model.gguf")
        // Epoch mismatch causes in-flight generate to discard output
        val output = driver.generate("test")
        assertNull(output)
    }
}
