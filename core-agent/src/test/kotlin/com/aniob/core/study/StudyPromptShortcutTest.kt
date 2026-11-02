package com.aniob.core.study

import com.aniob.core.ladder.AniobIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shortcut layer must make the study battery cheaper without answering it.
 *
 * Every prompt in [StudyBattery] asks for a visible end state ("leaving … showing …"), so none of
 * them may be claimed as complete by an intent: each one gets at most a zero-model *first move*
 * (an alarm editor already filled in, the Wi-Fi panel, a prefilled contact) and the run still ends
 * on the verifier's evidence. This test is the guard against a future shortcut quietly turning a
 * multi-step task into a one-line success.
 */
class StudyPromptShortcutTest {

    private fun shortcutFor(id: String) =
        AniobIntentResolver.resolve(StudyBattery.getTaskById(id)!!.prompt)

    @Test
    fun `no study prompt is claimed as finished by a shortcut`() {
        for (task in StudyBattery.tasks) {
            val shortcut = AniobIntentResolver.resolve(task.prompt)
            assertFalse(
                "${task.id} must not complete from an intent alone",
                shortcut?.completesTask == true
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
            "the prompt asks for the alarm list on screen",
            first?.extras?.containsKey(AniobIntentResolver.EXTRA_ALARM_SKIP_UI) == true
        )

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
    }

    @Test
    fun `contact prompt prefills name and phone`() {
        val contact = shortcutFor("study_04_contacts_create_john_study")
        assertEquals(AniobIntentResolver.ACTION_INSERT, contact?.action)
        assertEquals("John Study", contact?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_NAME))
        assertEquals("9876543210", contact?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_PHONE))
    }

    @Test
    fun `settings prompts open the named panel directly`() {
        assertEquals(
            AniobIntentResolver.ACTION_WIFI_SETTINGS,
            shortcutFor("study_07_settings_wifi")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_SAVER_SETTINGS,
            shortcutFor("study_08_settings_battery")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BLUETOOTH_SETTINGS,
            shortcutFor("study_09_settings_bluetooth")?.action
        )
    }

    @Test
    fun `compound app prompts are left to the app launch rung`() {
        // "Open Calculator and compute 12 times 8" is not "open the calculator": a complete
        // shortcut here would score the task without pressing a single digit.
        for (id in listOf(
            "study_10_calculator_multiply_12_8",
            "study_11_calculator_add_145_55",
            "study_12_calculator_multiply_7_6",
            "study_13_chrome_new_tab",
            "study_14_chrome_downloads",
            "study_15_chrome_history",
            "study_05_contacts_search_john_study",
            "study_06_contacts_add_email_john_study"
        )) {
            assertNull("$id must fall through to the ladder", shortcutFor(id))
        }
    }

    @Test
    fun `the battery status reflex is not swallowed by the battery panel`() {
        val status = AniobIntentResolver.resolve("check battery")
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            status?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
        assertTrue(status?.completesTask == true)
    }
}
