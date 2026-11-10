package com.aniob.core.grillme

import com.aniob.core.safety.AskCategory
import com.aniob.core.safety.AskLevel
import com.aniob.core.safety.TaskConsent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobUpfrontPlannerTest {

    private var clarifyCalls = 0
    private val fourQuestions = AniobGrillMeResult(
        needsClarification = true,
        questions = listOf(
            GrillQuestion("destination", "Where to?", emptyList()),
            GrillQuestion("ride_type", "Which tier?", listOf("Cheapest", "Fastest")),
            GrillQuestion("provider_app", "Which app?", listOf("Uber")),
            GrillQuestion("extra", "One more?", emptyList())
        )
    )

    private suspend fun plan(prompt: String, level: AskLevel = AskLevel.BALANCED, intent: Boolean = false) =
        AniobUpfrontPlanner.plan(prompt, level, emptyMap(), intent) { _, _ ->
            clarifyCalls++
            fourQuestions
        }

    @Test
    fun intentTasksNeverThink() = runBlocking {
        val plan = plan("open settings", intent = true)
        assertEquals("the model must not be consulted for an intent task", 0, clarifyCalls)
        assertTrue(plan.skippedThinking)
        assertFalse(plan.needsAsk)
    }

    @Test
    fun clarificationsAreCappedAndOrdered() = runBlocking {
        val plan = plan("book a cab")
        assertEquals(1, clarifyCalls)
        assertEquals(listOf("destination", "ride_type", "provider_app"), plan.clarifications.map { it.id })
        assertEquals("cap keeps the queue short", AniobUpfrontPlanner.MAX_CLARIFICATIONS, plan.clarifications.size)
    }

    @Test
    fun paymentsAreAskedOnceAtTheStartEvenForIntentTasks() = runBlocking {
        val plan = plan("pay my electricity bill", intent = true)
        assertEquals(0, clarifyCalls)
        assertEquals(listOf("consent.money"), plan.consents.map { it.id })
        val q = plan.consents.single()
        assertEquals(listOf(TaskConsent.OPT_ALLOW, TaskConsent.OPT_DENY), q.options)
        assertFalse("consent must be an explicit choice, never typed", q.allowFreeText)
        assertEquals("nothing is pre-selected", null, q.defaultOption)
    }

    @Test
    fun deleteAndSecretAreAskedTogetherAfterClarifications() = runBlocking {
        val plan = plan("delete my old photos and enter my password")
        assertEquals(listOf("consent.secret", "consent.delete"), plan.consents.map { it.id })
        assertEquals(plan.clarifications + plan.consents, plan.questions)
    }

    @Test
    fun plainTaskAsksNothing() = runBlocking {
        val plan = AniobUpfrontPlanner.plan("open youtube", AskLevel.BALANCED, emptyMap(), false) { _, _ ->
            AniobGrillMeResult(needsClarification = false)
        }
        assertFalse(plan.needsAsk)
    }

    @Test
    fun sendingWhatYouTypedIsImplicitConsentUnlessStrict() = runBlocking {
        val balanced = plan("message Asha that I'm late")
        assertTrue(AskCategory.SEND in balanced.implicitConsent)
        assertTrue("no send question in balanced mode", balanced.consents.none { it.id == "consent.send" })

        val strict = plan("message Asha that I'm late", level = AskLevel.CAREFUL)
        assertTrue(strict.implicitConsent.isEmpty())
        assertTrue(strict.consents.any { it.id == "consent.send" })
        assertTrue(strict.consents.any { it.id == TaskConsent.ID_OTHER })
    }

    @Test
    fun readOnlyHistoryIsNotAPayment() = runBlocking {
        val plan = plan("show my payment history", intent = true)
        assertTrue(plan.consents.isEmpty())
    }

    @Test
    fun modelCannotSmuggleConsentQuestionsIn() = runBlocking {
        val plan = AniobUpfrontPlanner.plan("book a cab", AskLevel.BALANCED, emptyMap(), false) { _, _ ->
            AniobGrillMeResult(true, listOf(GrillQuestion("consent.money", "Pay?", listOf("Allow"))))
        }
        assertTrue(plan.clarifications.isEmpty())
    }
}
