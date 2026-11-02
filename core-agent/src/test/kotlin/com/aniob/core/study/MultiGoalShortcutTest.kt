package com.aniob.core.study

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.ladder.AniobIntentResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A shortcut may only close a run when it answered the whole prompt.
 *
 * The eval failures this pins down were all the same shape: the first clause of a two-clause
 * prompt was dispatched, the run was closed, and the second half was never done (or a status
 * question was "answered" by opening a settings screen). These cases keep the shortcut — it is
 * still the cheapest first move — while forcing the run to continue.
 */
class MultiGoalShortcutTest {

    @Before
    fun setUp() {
        AniobAppRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        AniobAppRegistry.resetForTest()
    }

    @Test
    fun `a second clause keeps the alarm editor visible`() {
        val result = AniobIntentResolver.resolve("set an alarm for 7:30 and open instagram")

        assertEquals(AniobIntentResolver.ACTION_SET_ALARM, result?.action)
        assertEquals("7", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_HOUR))
        assertFalse(
            "a silent alarm would leave the instagram half undone",
            result?.extras?.containsKey(AniobIntentResolver.EXTRA_ALARM_SKIP_UI) == true
        )
        assertTrue("the editor has to be looked at", result?.requiresObservation == true)
    }

    @Test
    fun `a single goal alarm still completes from the dispatch`() {
        val result = AniobIntentResolver.resolve("set an alarm for 7:30 AM")

        assertNotNull(result)
        assertEquals("true", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_SKIP_UI))
        assertFalse("nothing else was asked for", result?.requiresObservation == true)
    }

    @Test
    fun `a status question is not answered by opening the panel`() {
        val result = AniobIntentResolver.resolve("open settings and check battery")

        assertEquals(AniobIntentResolver.ACTION_BATTERY_USAGE, result?.action)
        assertTrue("the level still has to be read", result?.requiresObservation == true)
    }

    @Test
    fun `the battery screen is the usage screen, not the saver switch`() {
        val result = AniobIntentResolver.resolve("open battery saver")
        assertEquals(AniobIntentResolver.ACTION_BATTERY_SAVER_SETTINGS, result?.action)

        val usage = AniobIntentResolver.resolve("open the battery screen")
        assertEquals(AniobIntentResolver.ACTION_BATTERY_USAGE, usage?.action)
    }

    @Test
    fun `the panel named first wins over the table order`() {
        val result = AniobIntentResolver.resolve("open airplane mode and wifi settings")

        assertEquals(AniobIntentResolver.ACTION_AIRPLANE_MODE_SETTINGS, result?.action)
        assertTrue("wifi settings is still owed", result?.requiresObservation == true)
    }

    @Test
    fun `longest key wins when two panel names start together`() {
        // "battery saver" and "battery" start at the same offset; the specific name must win.
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_SAVER_SETTINGS,
            AniobIntentResolver.resolve("open power saving mode")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_SAVER_SETTINGS,
            AniobIntentResolver.resolve("open battery saver settings")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_USAGE,
            AniobIntentResolver.resolve("open battery usage")?.action
        )
    }

    @Test
    fun `a second clause keeps the timer running on screen`() {
        val result = AniobIntentResolver.resolve("start a timer for 10 minutes then open instagram")

        assertEquals(AniobIntentResolver.ACTION_SET_TIMER, result?.action)
        assertEquals("600", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_LENGTH))
        assertFalse(result?.extras?.containsKey(AniobIntentResolver.EXTRA_ALARM_SKIP_UI) == true)
        assertTrue(result?.requiresObservation == true)
    }

    @Test
    fun `a single clause panel still closes the run`() {
        val result = AniobIntentResolver.resolve("open Wi-Fi settings")
        assertEquals(AniobIntentResolver.ACTION_WIFI_SETTINGS, result?.action)
        assertFalse("the panel is the whole prompt", result?.requiresObservation == true)
    }
}
