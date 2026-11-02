package com.aniob.core.study

import com.aniob.core.ladder.AniobIntentResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The shortcut layer must make the study battery cheaper without answering it for them.
 *
 * A prompt may only be claimed complete by an intent when the dispatch itself is the end state the
 * prompt asked for: opening the Wi-Fi panel is that state, while a silent alarm is not when the
 * task says "leaving the alarm list showing 7:30 AM", and a prefilled contact editor still needs
 * its Save tap. This test pins that line down for all fifteen study prompts.
 */
class StudyPromptShortcutTest {

    @Before
    fun setUp() {
        com.aniob.core.knowledge.AniobAppRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        com.aniob.core.knowledge.AniobAppRegistry.resetForTest()
    }

    private fun shortcutFor(id: String) =
        AniobIntentResolver.resolve(StudyBattery.getTaskById(id)!!.prompt)

    @Test
    fun `no study prompt is silently completed by an intent except the settings panels`() {
        for (task in StudyBattery.tasks) {
            val shortcut = AniobIntentResolver.resolve(task.prompt)
            val silentlyComplete = shortcut?.requiresObservation == false
            val settingsPanel = task.id.startsWith("study_07") || task.id.startsWith("study_08") ||
                task.id.startsWith("study_09")
            assertEquals(
                "${task.id}: only an open panel may be the whole task",
                settingsPanel,
                silentlyComplete
            )
        }
    }

    @Test
    fun `alarm prompts open a prefilled editor without the silent skip-ui path`() {
        val first = shortcutFor("study_01_clock_alarm_730am")
        assertNotNull(first)
        assertEquals(AniobIntentResolver.ACTION_SET_ALARM, first?.action)
        assertEquals("7", first?.extras?.get(AniobIntentResolver.EXTRA_ALARM_HOUR))
        assertEquals("30", first?.extras?.get(AniobIntentResolver.EXTRA_ALARM_MINUTES))
        assertFalse(
            "the prompt asks to see the alarm list",
            first?.extras?.containsKey(AniobIntentResolver.EXTRA_ALARM_SKIP_UI) == true
        )
        assertTrue("the visible result still has to be observed", first?.requiresObservation == true)

        val second = shortcutFor("study_02_clock_alarm_615pm")
        assertEquals("18", second?.extras?.get(AniobIntentResolver.EXTRA_ALARM_HOUR))
        assertEquals("15", second?.extras?.get(AniobIntentResolver.EXTRA_ALARM_MINUTES))
    }

    @Test
    fun `timer prompt starts the timer in seconds and keeps the ui`() {
        val timer = shortcutFor("study_03_clock_timer_5min")
        assertEquals(AniobIntentResolver.ACTION_SET_TIMER, timer?.action)
        assertEquals("300", timer?.extras?.get(AniobIntentResolver.EXTRA_ALARM_LENGTH))
        assertFalse(timer?.extras?.containsKey(AniobIntentResolver.EXTRA_ALARM_SKIP_UI) == true)
        assertTrue(timer?.requiresObservation == true)
    }

    @Test
    fun `contact prompt prefills name and phone but still needs the save tap`() {
        val contact = shortcutFor("study_04_contacts_create_john_study")
        assertEquals(AniobIntentResolver.ACTION_INSERT, contact?.action)
        assertEquals("John Study", contact?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_NAME))
        assertEquals("9876543210", contact?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_PHONE))
        assertTrue(contact?.requiresObservation == true)
    }

    @Test
    fun `settings prompts open their panel and stop`() {
        assertEquals(
            AniobIntentResolver.ACTION_WIFI_SETTINGS,
            shortcutFor("study_07_settings_wifi")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_USAGE,
            shortcutFor("study_08_settings_battery")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BLUETOOTH_SETTINGS,
            shortcutFor("study_09_settings_bluetooth")?.action
        )
        for (id in listOf("study_07_settings_wifi", "study_08_settings_battery", "study_09_settings_bluetooth")) {
            assertFalse("the panel is the requested end state", shortcutFor(id)?.requiresObservation == true)
        }
    }

    @Test
    fun `in-app work prompts are never claimable by a shortcut`() {
        // "compute 12 times 8", "open a new tab", "search for John": the intent can at most open
        // the app, and that launch is observable — never a finished task.
        for (id in listOf(
            "study_05_contacts_search_john_study",
            "study_06_contacts_add_email_john_study",
            "study_10_calculator_multiply_12_8",
            "study_11_calculator_add_145_55",
            "study_12_calculator_multiply_7_6",
            "study_13_chrome_new_tab",
            "study_14_chrome_downloads",
            "study_15_chrome_history"
        )) {
            val shortcut = shortcutFor(id)
            assertFalse("$id must not be claimed complete", shortcut?.requiresObservation == false)
        }
    }

    @Test
    fun `the battery status reflex is not swallowed by the battery panel`() {
        val status = AniobIntentResolver.resolve("check battery")
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            status?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
        assertNull(status?.requiresObservation?.takeIf { it })
    }
}
