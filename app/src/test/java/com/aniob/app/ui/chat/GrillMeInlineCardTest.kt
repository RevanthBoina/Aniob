package com.aniob.app.ui.chat

import com.aniob.core.grillme.GrillQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GrillMeInlineCardTest {
    private val questions = listOf(
        GrillQuestion("recipient", "Who should receive this?", listOf("A", "B")),
        GrillQuestion("app", "Which app?", listOf("X"), defaultOption = "X")
    )

    @Test fun nothingIsSubmittedWhileAQuestionIsUnanswered() {
        assertNull(grillAnswersToSubmit(questions, mapOf("recipient" to "", "app" to "X"), ""))
        assertNull(grillAnswersToSubmit(questions, mapOf("app" to "X"), "note"))
    }

    @Test fun completeAnswersAreSubmittedWithOptionalNote() {
        val answers = mapOf("recipient" to "typed by hand", "app" to "X")
        assertEquals(answers, grillAnswersToSubmit(questions, answers, "  "))
        assertEquals(answers + ("notes" to "be quick"), grillAnswersToSubmit(questions, answers, "be quick"))
    }
}
