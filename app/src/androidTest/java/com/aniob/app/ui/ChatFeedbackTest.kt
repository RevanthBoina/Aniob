package com.aniob.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aniob.app.ui.chat.ChatMessage
import com.aniob.app.ui.screens.StreamingChatBubble
import com.aniob.app.ui.screens.WhatsAppChatBubble
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingAnswerHasVisibleFeedback() {
        compose.setContent { MaterialTheme { StreamingChatBubble("") } }
        compose.onNodeWithTag("chat_generating_bubble").assertIsDisplayed()
        compose.onNodeWithText("Waiting for an answer...").assertIsDisplayed()
    }

    @Test fun unavailableAnswerOffersRetry() {
        var retried = false
        val message = ChatMessage(id = "failure", role = "assistant", content = "Could not answer.",
            badge = "Answer unavailable", retryPrompt = "Original question")
        compose.setContent {
            MaterialTheme {
                WhatsAppChatBubble(message, onOpenDetails = {}, onRetry = { retried = true })
            }
        }
        compose.onNodeWithText("Answer unavailable").assertIsDisplayed()
        compose.onNodeWithTag("doctor_retry_btn").performClick()
        assertTrue(retried)
    }
}
