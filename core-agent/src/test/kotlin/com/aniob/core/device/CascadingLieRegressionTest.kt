package com.aniob.core.device

import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.DeviceCommand
import com.aniob.core.intent.AniobIntent
import com.aniob.core.intent.AniobTaskIntentClassifier
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobReflexResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for the 2026-11-04 post-build report, where 5 of 6 tasks were stamped
 * SUCCESS while zero device actions executed: telegraphic torch commands fell through to a chat
 * answer (isSuccess = HTTP 200), compound goals were bounced by design, "turn on flashlight" was
 * absent from the ON lexicon, and "install linkedin" had no deep-link rung.
 */
class CascadingLieRegressionTest {

    @Before fun setUp() = AniobAppRegistry.resetForTest()
    @After fun tearDown() = AniobAppRegistry.resetForTest()

    private fun reflex(prompt: String) = AniobIntentResolver.resolve(prompt)?.let {
        AniobReflexResolver.commandOf(it)?.let { cmd -> cmd to AniobReflexResolver.argsOf(it) }
    }

    // ------------------------------------------------------------------ BUG-02: classification --

    @Test
    fun telegraphicTorchCommandsAreDeviceIntent() {
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("on flash light"))
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("on and off flash light"))
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("flashlight off"))
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("torch on please"))
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("turn on flashlight"))
    }

    @Test
    fun genuineConversationStaysConversational() {
        assertEquals(AniobIntent.EXTERNAL_AI_QUERY, AniobTaskIntentClassifier.classify("hello"))
        assertEquals(AniobIntent.KNOWLEDGE_QA, AniobTaskIntentClassifier.classify("what is the capital of france"))
    }

    // ------------------------------------------------------------------ BUG-03: torch lexicon ---

    @Test
    fun torchAliasesResolveWithZeroTokens() {
        // New telegraphic forms land on the verified device-control reflex.
        assertEquals(DeviceCommand.TORCH_ON, reflex("on flash light")?.first)
        assertEquals(DeviceCommand.TORCH_ON, reflex("on torch")?.first)
        assertEquals(DeviceCommand.TORCH_ON, reflex("on flashlight")?.first)
        assertEquals(DeviceCommand.TORCH_OFF, reflex("off flashlight")?.first)
        assertEquals(DeviceCommand.TORCH_OFF, reflex("flashlight off")?.first)
        // The legacy system rung claims these first; they must still resolve as shortcuts.
        for (phrase in listOf("turn on flashlight", "flashlight on", "flashlight")) {
            val legacy = AniobIntentResolver.resolve(phrase)
            assertNotNull("$phrase must resolve", legacy)
            assertEquals(
                AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT,
                legacy?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
            )
        }
    }

    // ------------------------------------------------------------------ BUG-04: sequences -------

    @Test
    fun compoundTorchDecomposesIntoVerifiedSequence() {
        val (cmd, args) = reflex("on and off flash light")!!
        assertEquals(DeviceCommand.TORCH_ON, cmd)
        assertEquals(DeviceCommand.TORCH_OFF.wire, args["then_command"])
        assertEquals("500", args["then_delay_ms"])

        assertEquals(
            "5000",
            reflex("turn on flashlight and turn it off after 5 seconds")!!.second["then_delay_ms"]
        )
        assertEquals(
            "10000",
            reflex("flashlight on and off after 10 seconds")!!.second["then_delay_ms"]
        )
        assertEquals(
            "5000",
            reflex("on and off after 5 seconds flashlight")!!.second["then_delay_ms"]
        )
    }

    @Test
    fun unrelatedMultiGoalStillBounces() {
        // The decomposition is torch-scoped; other compound goals keep the model path.
        assertNull(reflex("pause music and open instagram"))
    }

    // ------------------------------------------------------------------ BUG-05: install rung ----

    @Test
    fun installResolvesToOneMarketDeepLink() {
        val shortcut = AniobIntentResolver.resolve("install linkedin")
        assertNotNull(shortcut)
        assertEquals("android.intent.action.VIEW", shortcut?.action)
        assertEquals("market://details?id=com.linkedin.android", shortcut?.uriString)
        assertEquals("com.android.vending", shortcut?.preferredPackage)
        assertEquals("com.linkedin.android", shortcut?.extras?.get(AniobIntentResolver.EXTRA_INSTALL_PACKAGE))
        assertTrue(shortcut?.requiresObservation == true)
    }

    @Test
    fun unknownInstallFallsBackToStoreSearch() {
        val shortcut = AniobIntentResolver.resolve("download nebula reader")
        assertNotNull(shortcut)
        assertTrue(shortcut?.uriString?.startsWith("market://search?q=") == true)
    }

    // ------------------------------------------------------------------ BUG-06: criteria --------

    @Test
    fun torchPromptsProduceDeviceAssertions() {
        assertEquals(
            listOf(DeviceAssertion.TORCH_ON),
            AniobTaskIntentClassifier.extractCriteria("on flash light").deviceAsserts
        )
        // Last cue wins: an on/off pair ends OFF.
        assertEquals(
            listOf(DeviceAssertion.TORCH_OFF),
            AniobTaskIntentClassifier.extractCriteria("on and off flash light").deviceAsserts
        )
    }

    @Test
    fun installRequiresPlayStoreForeground() {
        val criteria = AniobTaskIntentClassifier.extractCriteria("install linkedin")
        assertTrue("com.android.vending" in criteria.mustShowPackage)
        assertEquals(listOf("com.linkedin.android"), criteria.mustBeInstalled)
        assertTrue(criteria.requireInstallConfirmation)
    }
}
