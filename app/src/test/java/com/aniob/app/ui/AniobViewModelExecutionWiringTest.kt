package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
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
class AniobViewModelExecutionWiringTest {

    @Test
    fun setFastPathEnabledSynchronizesRouterAndUiState() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.setFastPathEnabled(false)
        assertFalse("uiState.fastPathEnabled must be false", vm.uiState.value.fastPathEnabled)
        assertFalse("executionRouter.fastPathEnabled must be synced to false", vm.executionRouter.fastPathEnabled)

        vm.setFastPathEnabled(true)
        assertTrue("uiState.fastPathEnabled must be true", vm.uiState.value.fastPathEnabled)
        assertTrue("executionRouter.fastPathEnabled must be synced to true", vm.executionRouter.fastPathEnabled)
    }

    @Test
    fun queueFollowUpStoresSingleQueuedTaskAndCancelsGracefully() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.submitTask("First in-flight task")
        assertTrue("Task should be marked running", vm.uiState.value.isRunning)

        vm.queueFollowUp("Follow-up second task")
        assertEquals("Follow-up second task", vm.uiState.value.queuedPrompt)

        // Queueing another replaces or keeps single-owner queue
        vm.queueFollowUp("Follow-up third task")
        assertEquals("Follow-up third task", vm.uiState.value.queuedPrompt)

        vm.cancelQueuedFollowUp()
        assertNull("Queued prompt should be cleared after cancellation", vm.uiState.value.queuedPrompt)

        vm.stopCurrentTask()
    }

    @Test
    fun preflightOfflineShortcutExemption() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        // "turn on flashlight" or "open website https://example.com" should resolve as offline/intent shortcuts
        val doctorResult = vm.checkDoctorPreflight("open website https://example.com")
        assertTrue("Offline/intent shortcut must pass doctor preflight even without model downloads", doctorResult.allPassed)
    }

    @Test
    fun updateSettingsPersistsOmniRouteKeyModelAndBaseUrlAndRefreshesSystemState() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val vm = AniobViewModel(app)

        vm.updateSettings(
            apiKey = "sk-test-omniroute-123",
            model = "auto/smart",
            baseUrl = "https://omniroute.online/v1"
        )

        assertEquals("sk-test-omniroute-123", vm.uiState.value.omnirouteApiKey)
        assertEquals("auto/smart", vm.uiState.value.omnirouteModel)
        assertEquals("https://omniroute.online/v1", vm.uiState.value.omnirouteBaseUrl)
        assertTrue("SystemState modelReady must be true after saving OmniRoute API key", app.systemState.state.value.modelReady)
        assertTrue("Active brain label must reflect Cloud when configured", vm.getActiveBrainLabel().contains("Cloud"))

        // Clean up test state
        vm.updateSettings(apiKey = "", model = "auto", baseUrl = "")
    }

    @Test
    fun selectedAccountDoesNotBecomeReadyFromAnUnrelatedGatewayKey() {
        val app = ApplicationProvider.getApplicationContext<AniobApplication>()
        val secrets = com.aniob.app.data.AniobSecretStore(app)
        val source = app.accountAuth.cloudSource
        val key = secrets.getOmniRouteApiKey()
        val address = secrets.getOmniRouteBaseUrl()
        try {
            secrets.setOmniRouteApiKey("test-key")
            secrets.setOmniRouteBaseUrl("https://gateway.example.test/v1")
            app.accountAuth.cloudSource = com.aniob.core.auth.AniobCloudSource.ACCOUNT
            app.systemState.refresh()
            assertFalse(app.systemState.state.value.cloudReady)
            app.accountAuth.cloudSource = com.aniob.core.auth.AniobCloudSource.OMNIROUTE
            app.systemState.refresh()
            assertTrue(app.systemState.state.value.cloudReady)
        } finally {
            secrets.setOmniRouteApiKey(key)
            secrets.setOmniRouteBaseUrl(address)
            app.accountAuth.cloudSource = source
            app.systemState.refresh()
        }
    }

    @Test
    fun intentShortcutClosingRequiresObservableScreenCriteria() {
        val bareCriteria = com.aniob.core.domain.SuccessCriteria(minSteps = 1)
        assertFalse("Bare minSteps has no observable check", bareCriteria.hasObservableCheck)

        val observableCriteria = com.aniob.core.domain.SuccessCriteria(
            minSteps = 1,
            mustContainText = listOf("Wi-Fi")
        )
        assertTrue("Criteria with text expectation has observable check", observableCriteria.hasObservableCheck)

        val landedScreen = com.aniob.core.domain.AniobScreenState(
            packageName = "com.android.settings",
            nodes = listOf(com.aniob.core.domain.AniobNode(id = 1, className = "android.widget.TextView", text = "Wi-Fi"))
        )

        val bareVerdict = if (bareCriteria.hasObservableCheck) {
            bareCriteria.evaluateCriteria(landedScreen, 1)
        } else {
            emptyList()
        }
        assertTrue("Bare criteria produces empty verdict falling through to UNVERIFIED", bareVerdict.isEmpty())

        val observableVerdict = if (observableCriteria.hasObservableCheck) {
            observableCriteria.evaluateCriteria(landedScreen, 1)
        } else {
            emptyList()
        }
        assertTrue("Observable criteria evaluates to non-empty verdict", observableVerdict.isNotEmpty())
        assertTrue("All criteria passed on landed screen", observableVerdict.all { it.state == com.aniob.core.domain.CriterionState.PASSED })
    }
}
