package com.aniob.core

import com.aniob.core.ladder.AniobIntentResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobIntentResolverTest {

    @Test
    fun testOpenSettingsResolvesDirectly() {
        val result = AniobIntentResolver.resolve("Open Settings")
        assertNotNull(result)
        assertEquals("com.android.settings", result?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, result?.action)
    }

    @Test
    fun testCallNumberResolvesToDialIntent() {
        val result = AniobIntentResolver.resolve("call 9876543210")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_DIAL, result?.action)
        assertEquals("tel:9876543210", result?.uriString)
    }

    @Test
    fun testComplexTaskReturnsNullForIntent() {
        val result = AniobIntentResolver.resolve("Book a cab to downtown")
        assertNull("Complex task should not be resolved as single intent shortcut", result)
    }

    @Test
    fun testTurnOnFlashlightResolvesToReflex() {
        val result = AniobIntentResolver.resolve("Turn on flashlight")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_FLASHLIGHT, result?.action)
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_FLASHLIGHT,
            result?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
    }

    @Test
    fun testCheckBatteryResolvesToReflex() {
        val result = AniobIntentResolver.resolve("Check battery")
        assertNotNull(result)
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            result?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
    }

    @Test
    fun testFlashlightVariantsResolveCaseInsensitive() {
        assertNotNull(AniobIntentResolver.resolve("Flashlight ON"))
        assertNotNull(AniobIntentResolver.resolve("open the flashlight"))
        assertNotNull(AniobIntentResolver.resolve("What is my battery?"))
    }

    @Test
    fun testOpenWebsitePreservesCasingAndQuery() {
        val result = AniobIntentResolver.resolve("open website https://example.com/PathWithCaps?Param=Value123")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_VIEW, result?.action)
        assertEquals("https://example.com/PathWithCaps?Param=Value123", result?.uriString)
    }

    @Test
    fun testOpenWebsiteRejectsCredentialsAndControlChars() {
        val credResult = AniobIntentResolver.resolve("open website https://user:pass@example.com/secret")
        assertNull("URLs with embedded credentials must be rejected", credResult)

        val ctrlResult = AniobIntentResolver.resolve("open website https://example.com/\u0000bad")
        assertNull("URLs with control characters must be rejected", ctrlResult)

        val jsResult = AniobIntentResolver.resolve("open website javascript:alert(1)")
        assertNull("JavaScript scheme must be rejected", jsResult)
    }

    @Test
    fun testOpenClockAndAlarmResolveDirectly() {
        val clock = AniobIntentResolver.resolve("Open Clock")
        assertNotNull(clock)
        assertEquals("android.intent.action.SHOW_ALARMS", clock?.action)

        val alarm = AniobIntentResolver.resolve("Set alarm")
        assertNotNull(alarm)
        assertEquals("android.intent.action.SET_ALARM", alarm?.action)

        val spotify = AniobIntentResolver.resolve("Open Spotify")
        assertNotNull(spotify)
        assertEquals("com.spotify.music", spotify?.targetPackage)
    }

    @Test
    fun testOpenWhatsAppAndYouTubeResolveDirectly() {
        val wa1 = AniobIntentResolver.resolve("Open WhatsApp")
        assertNotNull(wa1)
        assertEquals("com.whatsapp", wa1?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, wa1?.action)

        val wa2 = AniobIntentResolver.resolve("launch whatsapp")
        assertNotNull(wa2)
        assertEquals("com.whatsapp", wa2?.targetPackage)

        val yt = AniobIntentResolver.resolve("Open YouTube")
        assertNotNull(yt)
        assertEquals("com.google.android.youtube", yt?.targetPackage)

        val tg = AniobIntentResolver.resolve("launch telegram")
        assertNotNull(tg)
        assertEquals("org.telegram.messenger", tg?.targetPackage)
    }

    @Test
    fun testDynamicAppCatalogResolution() {
        val catalog = com.aniob.core.knowledge.AniobAppCatalog.getInstance()
        catalog.registerApp(
            com.aniob.core.knowledge.AppCatalogEntry(
                packageName = "com.sample.customapp",
                appName = "Custom App",
                aliases = listOf("sample app")
            )
        )

        val res = AniobIntentResolver.resolve("Open Custom App")
        assertNotNull(res)
        assertEquals("com.sample.customapp", res?.targetPackage)
        assertEquals(AniobIntentResolver.ACTION_MAIN, res?.action)
    }

    // --- parameterised shortcuts -------------------------------------------------------------

    @Test
    fun alarmWithATimeSetsItWithoutUi() {
        val result = AniobIntentResolver.resolve("set an alarm for 7:30 am")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_SET_ALARM, result?.action)
        assertEquals("7", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_HOUR))
        assertEquals("30", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_MINUTES))
        assertEquals("true", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_SKIP_UI))
        assertTrue("an alarm with its time is the finished task", result?.completesTask == true)
    }

    @Test
    fun alarmInTheEveningConvertsToTwentyFourHours() {
        val result = AniobIntentResolver.resolve("set an alarm for 7:05pm")
        assertEquals("19", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_HOUR))
        assertEquals("5", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_MINUTES))
    }

    @Test
    fun alarmWithoutATimeOnlyOpensTheEditor() {
        val result = AniobIntentResolver.resolve("set alarm")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_SET_ALARM, result?.action)
        assertFalse("an empty alarm editor is not a set alarm", result?.completesTask == true)
    }

    @Test
    fun cancellingAnAlarmIsNeverTurnedIntoSettingOne() {
        val result = AniobIntentResolver.resolve("cancel my alarm for 7:00 am")
        assertTrue(result == null || result.action != AniobIntentResolver.ACTION_SET_ALARM)
    }

    @Test
    fun timerWithADurationStartsWithoutUi() {
        val result = AniobIntentResolver.resolve("set a timer for 10 minutes")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_SET_TIMER, result?.action)
        assertEquals("600", result?.extras?.get(AniobIntentResolver.EXTRA_ALARM_LENGTH))
        assertTrue(result?.completesTask == true)
    }

    @Test
    fun compoundDurationAddsUp() {
        assertEquals(5400, AniobIntentResolver.parseDurationSeconds("for 1 hour 30 minutes"))
        assertNull(AniobIntentResolver.parseDurationSeconds("for a while"))
    }

    @Test
    fun settingsPanelsUseTheirOwnActions() {
        val wifi = AniobIntentResolver.resolve("open wifi settings")
        assertEquals(AniobIntentResolver.ACTION_WIFI_SETTINGS, wifi?.action)
        assertTrue(wifi?.completesTask == true)

        assertEquals(
            AniobIntentResolver.ACTION_BLUETOOTH_SETTINGS,
            AniobIntentResolver.resolve("open bluetooth")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_BATTERY_SAVER_SETTINGS,
            AniobIntentResolver.resolve("open battery saver")?.action
        )
        assertEquals(
            AniobIntentResolver.ACTION_SETTINGS,
            AniobIntentResolver.resolve("open system settings")?.action
        )
    }

    @Test
    fun togglingWifiOpensThePanelButDoesNotClaimTheToggle() {
        val result = AniobIntentResolver.resolve("turn on wifi")
        assertEquals(AniobIntentResolver.ACTION_WIFI_SETTINGS, result?.action)
        assertFalse("opening the panel does not turn the radio on", result?.completesTask == true)
    }

    @Test
    fun batteryStatusIsNotSwallowedByTheBatteryPanel() {
        val result = AniobIntentResolver.resolve("check battery")
        assertEquals(
            AniobIntentResolver.SYSTEM_ACTION_GET_DEVICE_INFO,
            result?.extras?.get(AniobIntentResolver.EXTRA_SYSTEM_ACTION)
        )
    }

    @Test
    fun addingAContactPrefillsTheEditor() {
        val result = AniobIntentResolver.resolve("add contact John with number 5551234")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_INSERT, result?.action)
        assertEquals(AniobIntentResolver.CONTACT_MIME_TYPE, result?.dataType)
        assertEquals("John", result?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_NAME))
        assertEquals("5551234", result?.extras?.get(AniobIntentResolver.EXTRA_CONTACT_PHONE))
        assertFalse("the editor still needs a save tap", result?.completesTask == true)
    }

    @Test
    fun dialVsCallDecideCompletion() {
        val dial = AniobIntentResolver.resolve("dial 5551234")
        assertEquals(AniobIntentResolver.ACTION_DIAL, dial?.action)
        assertEquals("tel:5551234", dial?.uriString)
        assertTrue(dial?.completesTask == true)

        val call = AniobIntentResolver.resolve("call 5551234")
        assertEquals(AniobIntentResolver.ACTION_DIAL, call?.action)
        assertFalse("dialing the number is not placing the call", call?.completesTask == true)
    }

    @Test
    fun browserSearchBecomesAViewIntentOnTheQuery() {
        val result = AniobIntentResolver.resolve("search for pizza on google")
        assertNotNull(result)
        assertEquals(AniobIntentResolver.ACTION_VIEW, result?.action)
        assertTrue(result?.uriString?.startsWith(AniobIntentResolver.GOOGLE_SEARCH_URL) == true)
        assertTrue(result?.uriString?.contains("pizza") == true)
        assertTrue(result?.completesTask == true)
    }

    @Test
    fun webResearchPromptsStayOnTheAnswerPath() {
        assertNull(AniobIntentResolver.resolve("search the web for pizza"))
        assertNull(AniobIntentResolver.resolve("research the best pizza in town"))
    }

    @Test
    fun calculatorResolvesToInstalledPackages() {
        val result = AniobIntentResolver.resolve("open calculator")
        assertNotNull(result)
        assertTrue("the launcher needs candidate packages", (result?.targetPackages?.size ?: 0) > 1)
        assertTrue(result?.completesTask == true)
    }

    @Test
    fun openingANamedAppCompletesTheTask() {
        val result = AniobIntentResolver.resolve("open spotify")
        assertTrue("opening the app is the whole task", result?.completesTask == true)
    }

    @Test
    fun ordinarySentencesStillResolveToNothing() {
        assertNull(AniobIntentResolver.resolve("book a cab to downtown"))
        assertNull(AniobIntentResolver.resolve("order a pizza on whatsapp"))
    }
}
