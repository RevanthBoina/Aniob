package com.aniob.app.ui

import androidx.test.core.app.ApplicationProvider
import com.aniob.app.AniobApplication
import com.aniob.core.outcome.SealedOutcome
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = AniobApplication::class, sdk = [33])
class ConversationContextBoundaryTest {
    @Test fun switchingClearsCorrectionAndQueuedTaskOwnership() {
        val vm = AniobViewModel(ApplicationProvider.getApplicationContext())
        vm.setActiveConversation("old")
        vm._uiState.update { it.copy(lastSealedTaskId = "old-task", lastSealedOutcome = SealedOutcome.failed("old failure"),
            queuedPrompt = "old follow-up") }
        vm.setActiveConversation("new")
        assertNull(vm.uiState.value.lastSealedTaskId)
        assertNull(vm.uiState.value.lastSealedOutcome)
        assertNull(vm.uiState.value.queuedPrompt)
        assertFalse(vm.tryApplyNaturalLanguageCorrection("actually it worked"))
    }

    @Test fun newAndSwitchCommandsCannotChangeAnInFlightConversation() {
        val vm = AniobViewModel(ApplicationProvider.getApplicationContext())
        vm.setActiveConversation("busy-chat")
        vm._uiState.update { it.copy(isRunning = true) }
        vm.clearChat()
        assertEquals("busy-chat", vm.conversationId)
        assertTrue(vm.handleConversationCommand("/new Should not replace chat"))
        assertEquals("busy-chat", vm.conversationId)
    }
}
