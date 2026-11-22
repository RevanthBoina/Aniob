package com.aniob.core.capability

import com.aniob.core.grillme.AniobGrillMeEngine
import com.aniob.core.grillme.GrillChoice
import com.aniob.core.grillme.GrillQuestion
import org.junit.Assert.*
import org.junit.Test

class EntityChoiceQuestionTest {
    @Test fun `duplicate labels remain distinct and only IDs are accepted`() {
        val question = GrillQuestion("contact_choice", "Which contact?", emptyList(), allowFreeText = false,
            choices = listOf(GrillChoice("opaque-a", "Alex"), GrillChoice("opaque-b", "Alex")))
        assertTrue(question.acceptsAnswer("opaque-a"))
        assertTrue(question.acceptsAnswer("opaque-b", requireOption = true))
        assertFalse(question.acceptsAnswer("Alex"))
        assertFalse(question.acceptsAnswer("other"))
        assertFalse(question.acceptsAnswer(""))
        assertEquals(question, AniobGrillMeEngine().sanitizeQuestion(question))
    }

    @Test fun `legacy free text and closed choices remain supported`() {
        val closed = GrillQuestion("q", "Which?", listOf("One", "Two"), allowFreeText = false)
        assertTrue(closed.acceptsAnswer("One"))
        assertFalse(closed.acceptsAnswer("other"))
        assertFalse(closed.copy(options = listOf("")).acceptsAnswer(""))
        val open = closed.copy(allowFreeText = true)
        assertTrue(open.acceptsAnswer("other"))
        assertFalse(open.acceptsAnswer("other", requireOption = true))
    }
}
