package com.aniob.app.ui.chat

import com.aniob.core.grillme.GrillQuestion
import com.aniob.core.grillme.GrillChoice
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
    @Test fun entityChoiceSubmitsOpaqueIdNotItsLabel() {
        val q = GrillQuestion("contact_choice", "Which contact?", emptyList(), allowFreeText = false,
            choices = listOf(GrillChoice("id-1", "Alex"), GrillChoice("id-2", "Alex")))
        assertNull(grillAnswersToSubmit(listOf(q), mapOf(q.id to "Alex"), ""))
        assertNull(grillAnswersToSubmit(listOf(q), mapOf(q.id to "unoffered"), ""))
        assertEquals(mapOf(q.id to "id-2"), grillAnswersToSubmit(listOf(q), mapOf(q.id to "id-2"), ""))
    }
}
