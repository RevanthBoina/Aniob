package com.aniob.core.capability

import com.aniob.core.domain.DeviceCommand
import com.aniob.core.providers.AniobStructuredOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobCapabilityManifestTest {

    @Test
    fun everyCommandIsAdvertisedInBothSizes() {
        val full = AniobCapabilityManifest.render(compact = false)
        val compact = AniobCapabilityManifest.render(compact = true)
        DeviceCommand.entries.forEach { c ->
            assertTrue("${c.wire} missing from full manifest", full.contains(c.wire))
            assertTrue("${c.wire} missing from compact manifest", compact.contains(c.wire))
        }
    }

    @Test
    fun theManifestForbidsRefusalsAndPrefersTheFastRoute() {
        val text = AniobCapabilityManifest.render()
        assertTrue(text.contains("NEVER say you cannot operate the phone"))
        assertTrue(text.contains("DEVICE_CONTROL"))
        assertTrue(text.contains("FAIL naming that exact setting"))
    }

    @Test
    fun compactIsMuchSmallerThanFull() {
        val full = AniobCapabilityManifest.render(compact = false).length
        val compact = AniobCapabilityManifest.render(compact = true).length
        assertTrue("compact=$compact full=$full", compact < full * 0.7)
        assertTrue("compact manifest should stay small for 3B models: $compact", compact < 3600)
    }

    @Test
    fun liveStatusTellsTheModelWhatIsSwitchedOff() {
        val state = AniobCapabilityManifest.DeviceState(accessibility = false, notificationAccess = false)
        val text = AniobCapabilityManifest.render(state)
        assertTrue(text.contains("Accessibility is OFF"))
        assertTrue(text.contains("Notification access is OFF"))
        assertTrue(text.contains("[OFF:"))
        assertNotNull(AniobCapabilityManifest.blockedReason(DeviceCommand.OTP_COPY, state))
        assertNotNull(AniobCapabilityManifest.blockedReason(DeviceCommand.SCREENSHOT, state))
        assertNull(AniobCapabilityManifest.blockedReason(DeviceCommand.TORCH_ON, state))
        assertFalse(AniobCapabilityManifest.render().contains("Live status"))
    }

    @Test
    fun refusalGuardCatchesTheForbiddenReplies() {
        assertTrue(AniobRefusalGuard.isRefusal("I'm sorry, but I can't operate your phone."))
        assertTrue(AniobRefusalGuard.isRefusal("As an AI language model, I cannot access your device."))
        assertTrue(AniobRefusalGuard.isRefusal("I don't have access to your screen or apps."))
        assertTrue(AniobRefusalGuard.isRefusal("I am unable to control the volume on your device."))
    }

    @Test
    fun refusalGuardLeavesRealActionsAlone() {
        assertFalse(AniobRefusalGuard.isRefusal("""{"thought":"I can't see a button so scroll","tool":"SWIPE","direction":"DOWN"}"""))
        assertFalse(AniobRefusalGuard.isRefusal("Done. Opened Settings."))
        assertFalse(AniobRefusalGuard.isRefusal(null))
    }

    @Test
    fun aRefusalTurnsTheRepairPromptIntoACorrection() {
        val prompt = AniobStructuredOutput.repairPrompt("I'm sorry, I cannot operate your phone.")
        assertTrue(prompt.contains("you ARE connected to this phone"))
    }

    @Test
    fun identityLineNeverDeniesTheCapability() {
        assertTrue(AniobCapabilityManifest.IDENTITY_LINE.contains("Never claim to lack access"))
        assertEquals(1, Regex("wired into this Android phone").findAll(AniobCapabilityManifest.IDENTITY_LINE).count())
    }
}
