package com.aniob.app.ui

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aniob.app.ui.chat.OutcomeFeedbackRow
import com.aniob.app.ui.screens.AniobChatScreen
import com.aniob.core.grillme.AniobGrillMeResult
import com.aniob.core.grillme.GrillQuestion
import com.aniob.core.outcome.SealedOutcome
import com.aniob.core.outcome.UserDecision
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuditInteractionTest {
    @get:Rule val compose = createComposeRule()

    /** Run with an enabled software keyboard, in both gesture and three-button navigation. */
    @Test fun composerStaysAboveVisibleKeyboard() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            MaterialTheme {
                AniobChatScreen(AniobUiState(), onSubmitTask = {}, onStopTask = {},
                    onShowTrackerSheet = {}, onFilterChanged = {}, onManageModels = {})
            }
        }
        compose.onNodeWithTag("chat_input").performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        val bottom = compose.onNodeWithTag("chat_input").fetchSemanticsNode().boundsInWindow.bottom
        compose.runOnIdle {
            val ime = requireNotNull(ViewCompat.getRootWindowInsets(view)).getInsets(WindowInsetsCompat.Type.ime()).bottom
            assertTrue("Composer must not overlap IME", bottom <= view.rootView.height - ime + 1f)
        }
    }

    @Test fun consequentialQuestionDoesNotInventOrAutoSubmitAnAnswer() {
        var submissions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                AniobGrillMeSheet(AniobGrillMeResult(true, listOf(
                    GrillQuestion("recipient", "Who should receive this?", listOf("A", "B"))
                )), onConfirm = { _, _ -> submissions++ }, onDismiss = {})
            }
        }
        compose.mainClock.advanceTimeBy(11_000)
        compose.onNodeWithTag("grill_me_confirm").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun failedResultHasOneInlineCorrectionControl() {
        var decision: UserDecision? = null
        compose.setContent {
            MaterialTheme { OutcomeFeedbackRow(SealedOutcome.failed("Not observed"), { decision = it }) }
        }
        compose.onNodeWithTag("outcome_yes").assertTextContains("Mark correct").performClick()
        compose.runOnIdle { assertEquals(UserDecision.YES, decision) }
    }
}
