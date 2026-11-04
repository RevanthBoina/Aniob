package com.aniob.core.device

import com.aniob.core.domain.DeviceCommand
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobReflexResolver
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tier-1 phrases resolve with zero tokens; anything ambiguous, multi-goal or hard to undo does not. */
class AniobReflexResolverTest {

    @Before fun setUp() = AniobAppRegistry.resetForTest()
    @After fun tearDown() = AniobAppRegistry.resetForTest()

    private fun reflex(prompt: String) = AniobIntentResolver.resolve(prompt)?.let {
        AniobReflexResolver.commandOf(it)?.let { cmd -> cmd to AniobReflexResolver.argsOf(it) }
    }

    @Test
    fun mediaKeys() {
        assertEquals(DeviceCommand.MEDIA_PAUSE, reflex("Pause music")?.first)
        assertEquals(DeviceCommand.MEDIA_NEXT, reflex("skip this track")?.first)
        assertEquals(DeviceCommand.MEDIA_PLAY, reflex("resume music")?.first)
        assertEquals(DeviceCommand.MEDIA_PREVIOUS, reflex("previous song")?.first)
    }

    @Test
    fun playOnSpotifyBecomesAPlaySearch() {
        val (cmd, args) = reflex("Play Bohemian Rhapsody on Spotify")!!
        assertEquals(DeviceCommand.MEDIA_PLAY_SEARCH, cmd)
        assertEquals("bohemian rhapsody", args["query"])
        assertEquals("com.spotify.music", args["app"])
    }

    @Test
    fun playingAGameOrAVideoIsNotAMusicSearch() {
        assertNull(reflex("play chess"))
        assertNull(reflex("play cat videos on youtube"))
    }

    @Test
    fun volumePercentAndSteps() {
        val (cmd, args) = reflex("set volume to 70%")!!
        assertEquals(DeviceCommand.VOLUME_SET, cmd)
        assertEquals("70", args["percent"])
        assertEquals("ring", reflex("set ring volume to 30 percent")!!.second["stream"])
        assertEquals("100", reflex("set volume to max")!!.second["percent"])
        assertEquals(DeviceCommand.VOLUME_UP, reflex("volume up")?.first)
        assertEquals("ring", reflex("mute my phone")!!.second["stream"])
    }

    @Test
    fun torchOffScreenshotAndPanels() {
        assertEquals(DeviceCommand.TORCH_OFF, reflex("turn off the flashlight")?.first)
        assertEquals(DeviceCommand.SCREENSHOT, reflex("take a screenshot")?.first)
        assertEquals(DeviceCommand.OPEN_NOTIFICATIONS, reflex("pull down the notification shade")?.first)
        assertEquals(DeviceCommand.DND_ON, reflex("turn on do not disturb")?.first)
        assertEquals("internet", reflex("connect to home wifi")!!.second["name"])
    }

    @Test
    fun otpCopyIsAReflexButPasteIsNot() {
        assertEquals(DeviceCommand.OTP_COPY, reflex("copy the OTP")?.first)
        assertNull(reflex("paste the otp"))
    }

    @Test
    fun hardToUndoCommandsNeverResolveWithoutTheModel() {
        assertNull(reflex("call Mom on speaker"))
        assertNull(reflex("reply on my way to mom on whatsapp"))
        val risky = DeviceCommand.entries.filter { it.risk == com.aniob.core.domain.DeviceRisk.CONFIRM }
        assertTrue(risky.isNotEmpty())
    }

    @Test
    fun multiGoalSentencesAreLeftToTheModel() {
        assertNull(reflex("pause music and open instagram"))
        assertNull(reflex("take a screenshot then send it to mom"))
    }

    @Test
    fun whatsappAndTelegramDeepLinks() {
        val (cmd, args) = reflex("Send WhatsApp message to Alice: 'See you at 6'")!!
        assertEquals(DeviceCommand.MESSAGE_WHATSAPP, cmd)
        assertEquals("Alice", args["contact"])
        assertEquals("See you at 6", args["text"])
        assertEquals("+919876543210", reflex("whatsapp message to +91 98765 43210 saying hi")!!.second["phone"])
        assertEquals("Bob", reflex("Open chat with Bob in Telegram")!!.second["to"])
        assertTrue(AniobIntentResolver.resolve("Send WhatsApp message to Alice: hi")!!.requiresObservation)
    }

    @Test
    fun filesAndStorage() {
        val (cmd, args) = reflex("find the PDF I downloaded today")!!
        assertEquals(DeviceCommand.FILE_FIND, cmd)
        assertEquals("pdf", args["kind"])
        assertEquals("24", args["within_hours"])
        val open = reflex("open my latest bill")!!
        assertEquals(DeviceCommand.FILE_OPEN, open.first)
        assertEquals("bill", open.second["query"])
        assertEquals(DeviceCommand.STORAGE_INFO, reflex("how much storage is left")?.first)
    }

    @Test
    fun navigationAndExistingMapsBehaviourAreBothKept() {
        val (cmd, args) = reflex("get directions to MG Road Guntur")!!
        assertEquals(DeviceCommand.NAVIGATE, cmd)
        assertEquals("MG Road Guntur", args["destination"])
        assertEquals("home", reflex("navigate home")!!.second["destination"])
        // The older geo shortcut still owns "navigate to X".
        assertTrue(AniobIntentResolver.resolve("navigate to MG Road, Guntur")?.uriString?.startsWith("geo:0,0?q=") == true)
    }

    @Test
    fun reminderBecomesACalendarEntryWithAResolvedTime() {
        val clock = Clock.fixed(Instant.parse("2026-03-04T10:00:00Z"), ZoneOffset.UTC)
        val s = AniobReflexResolver.resolve(
            "Remind me tomorrow at 9 AM to pay rent",
            "remind me tomorrow at 9 am to pay rent", clock
        )
        assertNotNull(s)
        assertEquals(DeviceCommand.CALENDAR_INSERT, AniobReflexResolver.commandOf(s!!))
        val args = AniobReflexResolver.argsOf(s)
        assertEquals("pay rent", args["title"])
        assertEquals("2026-03-05T09:00:00", args["begin"])
        assertTrue(s.requiresObservation)
    }

    @Test
    fun aTimeThatAlreadyPassedRollsToTomorrow() {
        val clock = Clock.fixed(Instant.parse("2026-03-04T22:00:00Z"), ZoneOffset.UTC)
        assertEquals("2026-03-05T09:00:00", AniobReflexResolver.beginOf("", "9 am", clock))
        assertEquals("2026-03-04T21:00:00", AniobReflexResolver.beginOf("", "9 pm", Clock.fixed(Instant.parse("2026-03-04T10:00:00Z"), ZoneOffset.UTC)))
    }

    @Test
    fun silentReflexesCompleteOnDispatchAndEditorsDoNot() {
        assertTrue(AniobIntentResolver.resolve("pause music")!!.completesOnDispatch)
        assertFalse(AniobIntentResolver.resolve("add meeting 'Sprint Planning' to calendar")!!.completesOnDispatch)
    }
}
