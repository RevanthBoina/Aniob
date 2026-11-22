package com.aniob.core.intent

import com.aniob.core.ladder.AniobReflexResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single normalized command surface and the disposition/history routing built on it.
 * Pins the false-success cascade fixes: greetings/openers never create a command, creation verbs
 * stay generation, action-bearing requests never become prose answers.
 */
class CommandSurfaceTest {

    private val lower = { s: String -> s.lowercase() }

    // ------------------------------------------------------- normalizeCommandSurface ----------

    @Test
    fun `stacked greetings and polite openers are not the command`() {
        assertEquals("open settings", AniobTaskIntentClassifier.normalizeCommandSurface("Hello, open settings"))
        assertEquals("open settings", AniobTaskIntentClassifier.normalizeCommandSurface("hi, hey — can you please open settings"))
        assertEquals("turn on the flashlight", AniobTaskIntentClassifier.normalizeCommandSurface("Hey! Turn on the flashlight"))
    }

    @Test
    fun `greeting with nothing after it yields an empty command`() {
        assertEquals("", AniobTaskIntentClassifier.normalizeCommandSurface("hello"))
    }

    @Test
    fun `polite openers are not the command either`() {
        assertEquals("open settings", AniobTaskIntentClassifier.normalizeCommandSurface("please open settings"))
        assertEquals("open settings", AniobTaskIntentClassifier.normalizeCommandSurface("could you please open settings"))
    }

    @Test
    fun `command-word spelling is repaired, the payload is left alone`() {
        assertEquals("remind me to call mom tomorrow at 9", AniobTaskIntentClassifier.normalizeCommandSurface("remainde me to call mom tommorow at 9"))
        assertEquals("set a reminder for tomorrow", AniobTaskIntentClassifier.normalizeCommandSurface("set a remainder for tommorrow"))
    }

    // ------------------------------------------------------------- classify / dispose ----------

    @Test
    fun `a greeting-prefixed command is the command, not chat`() {
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("Hello, remind me to call mom tomorrow at 9"))
        assertEquals(AniobIntent.DEVICE_AUTOMATION, AniobTaskIntentClassifier.classify("hi open settings"))
    }

    @Test
    fun `vault lookups stay on the conversational route`() {
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("save my favorite color as blue"))
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("my preference for ride apps"))
    }

    @Test
    fun `generation requests stay conversational`() {
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("write a poem about spring"))
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("create a character for a novel"))
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("what is the capital of France"))
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("hello"))
        assertEquals(TaskDisposition.INFORMATION_REQUEST, AniobTaskIntentClassifier.dispose("thanks"))
    }

    @Test
    fun `action-bearing requests are never conversational`() {
        assertEquals(TaskDisposition.ACTION_REQUEST, AniobTaskIntentClassifier.dispose("turn on flashlight"))
        assertEquals(TaskDisposition.ACTION_REQUEST, AniobTaskIntentClassifier.dispose("send hi to mom"))
        assertEquals(TaskDisposition.ACTION_REQUEST, AniobTaskIntentClassifier.dispose("remind me to call mom tomorrow at 9"))
        assertEquals(TaskDisposition.AMBIGUOUS_REQUEST, AniobTaskIntentClassifier.dispose("open"))
    }

    @Test
    fun `task history questions route to the database report`() {
        assertEquals(AniobIntent.TASK_HISTORY, AniobTaskIntentClassifier.classify("show my last 10 tasks"))
        assertEquals(AniobIntent.TASK_HISTORY, AniobTaskIntentClassifier.classify("give me details of the last 10 tasks in copy-paste format"))
        assertEquals(AniobIntent.TASK_HISTORY, AniobTaskIntentClassifier.classify("task history"))
        assertEquals(AniobIntent.EXTERNAL_AI_QUERY, AniobTaskIntentClassifier.classify("what is the capital of France"))
    }

    @Test
    fun `task history query parsing extracts limit and id`() {
        val last10 = AniobTaskIntentClassifier.taskHistoryRequest("give me details of the last 10 tasks in copy-paste format")
        assertNotNull(last10)
        assertEquals(10, last10!!.limit)
        assertNull(last10.taskId)

        val last5 = AniobTaskIntentClassifier.taskHistoryRequest("show my last 5 tasks")
        assertNotNull(last5)
        assertEquals(5, last5!!.limit)

        val one = AniobTaskIntentClassifier.taskHistoryRequest("details of task_1732123456789")
        assertNotNull(one)
        assertEquals("task_1732123456789", one!!.taskId)

        assertNull(AniobTaskIntentClassifier.taskHistoryRequest("how do I use tasks in gradle?"))
    }

    // ---------------------------------------------------------- reminder slot extraction ---------

    @Test
    fun `order-independent reminder extraction keeps the subject spelling`() {
        val slots = AniobReflexResolver.extractReminderSlots("Set an remainder for tommorow at 9am for holi")
        assertNotNull(slots)
        assertEquals("tomorrow", slots!!.day)
        assertEquals("09:00", slots.time)
        assertEquals("holi", slots.title)

        val reversed = AniobReflexResolver.extractReminderSlots("Remainde me tommorow about holi at 5pm")
        assertNotNull(reversed)
        assertEquals("tomorrow", reversed!!.day)
        assertEquals("17:00", reversed.time)
        assertEquals("holi", reversed.title)
    }

    @Test
    fun `an absent time is left missing, never invented`() {
        val slots = AniobReflexResolver.extractReminderSlots("remind me to call mom tomorrow")
        assertNotNull(slots)
        assertEquals("tomorrow", slots!!.day)
        assertEquals("", slots.time)
        assertEquals("call mom", slots.title)
    }

    @Test
    fun `greeting and polite openers do not break reminder extraction`() {
        val greeted = AniobReflexResolver.extractReminderSlots("Hello, remind me to water plants tomorrow at 5pm")
        assertNotNull(greeted)
        assertEquals("water plants", greeted!!.title)
        assertEquals("17:00", greeted.time)

        val polite = AniobReflexResolver.extractReminderSlots("could you please remind me to water plants tomorrow at 5pm")
        assertNotNull(polite)
        assertEquals("water plants", polite!!.title)
        assertEquals("17:00", polite.time)
    }

    @Test
    fun `unparseable text is not a reminder`() {
        assertNull(AniobReflexResolver.extractReminderSlots("what time is it"))
        assertNull(AniobReflexResolver.extractReminderSlots("remind me at "))   // no subject
    }

    @Test
    fun `misspelled date words still land on the right day`() {
        val slots = AniobReflexResolver.extractReminderSlots("set a remainder for tommorow at 5pm to water plants")
        assertNotNull(slots)
        assertEquals("tomorrow", slots!!.day)
        assertEquals("17:00", slots.time)
    }

    @Test
    fun `a greeting before the reminder does not break the reflex`() {
        val shortcut = AniobReflexResolver.resolve(
            "Hello, remind me to water plants tomorrow at 5pm",
            lower("Hello, remind me to water plants tomorrow at 5pm")
        )
        assertNotNull("greeting-prefixed reminder must still resolve via reflex", shortcut)
        assertEquals(com.aniob.core.domain.DeviceCommand.CALENDAR_INSERT, AniobReflexResolver.commandOf(shortcut!!))
        assertEquals("water plants", AniobReflexResolver.argsOf(shortcut!!)["title"])
        assertTrue(AniobReflexResolver.argsOf(shortcut!!)["begin"]?.isNotBlank() == true)
    }

    @Test
    fun `a reminder with no time opens nothing until the user answers`() {
        // The reflex resolves it with an empty begin; the submit gate asks for the missing slot
        // instead of dispatching an open-ended calendar insert (tested at the app layer).
        val shortcut = AniobReflexResolver.resolve(
            "remind me to call mom tomorrow",
            lower("remind me to call mom tomorrow")
        )
        assertNotNull(shortcut)
        assertNull(AniobReflexResolver.argsOf(shortcut!!)["begin"])
    }
}
