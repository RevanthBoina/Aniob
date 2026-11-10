package com.aniob.core.grillme

import com.aniob.core.safety.TaskConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAskQueueTest {

    private val tier = GrillQuestion("ride_type", "Tier?", listOf("Cheapest", "Fastest"), allowFreeText = false, defaultOption = "Cheapest")
    private val dest = GrillQuestion("destination", "Where?", emptyList(), allowFreeText = true)
    private val pay = GrillQuestion("consent.money", "Allow payment?", listOf(TaskConsent.OPT_ALLOW, TaskConsent.OPT_DENY), allowFreeText = false)

    @Test
    fun showsOneQuestionAtATimeAndFinishes() {
        var q = AniobAskQueue.start(listOf(dest, tier, pay))
        assertEquals(dest, q.current)
        assertEquals(1, q.position)
        assertEquals(3, q.total)
        q = q.answer("Airport")
        assertEquals(tier, q.current)
        q = q.answer("Fastest")
        q = q.answer(TaskConsent.OPT_ALLOW)
        assertTrue(q.isComplete)
        assertEquals(mapOf("destination" to "Airport", "ride_type" to "Fastest", "consent.money" to "Allow"), q.answers)
    }

    @Test
    fun rejectsBlankAndUnofferedChoices() {
        val q = AniobAskQueue.start(listOf(tier))
        assertEquals(q, q.answer("   "))
        assertEquals("a closed question refuses text it never offered", q, q.answer("Teleport"))
    }

    @Test
    fun skipUsesDefaultButNeverAllowsConsent() {
        var q = AniobAskQueue.start(listOf(tier, dest, pay)).skip()
        assertEquals("Cheapest", q.answers["ride_type"])
        q = q.skip()
        assertFalse("a skipped free-text question is simply dropped", q.answers.containsKey("destination"))
        q = q.skip()
        assertEquals(TaskConsent.OPT_DENY, q.answers["consent.money"])
        assertTrue(q.isComplete)
    }

    @Test
    fun consentIsNeverRemembered() {
        val q = AniobAskQueue.start(listOf(tier, pay)).answer("Fastest", rememberChoice = true).answer("Allow", rememberChoice = true)
        assertEquals(setOf("ride_type"), q.remember)
    }

    @Test
    fun backReopensThePreviousQuestion() {
        val q = AniobAskQueue.start(listOf(dest, tier)).answer("Home").back()
        assertEquals(dest, q.current)
        assertFalse(q.back().canGoBack)
        assertEquals("Mall", q.answer("Mall").answers["destination"])
    }

    @Test
    fun emptyQueueIsAlreadyComplete() {
        assertTrue(AniobAskQueue.start(emptyList()).isComplete)
    }
}
