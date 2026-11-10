package com.aniob.core.grillme

import com.aniob.core.grillme.AniobAskUser.MidRunDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobAskUserTest {

    @Test
    fun parsesTheModelAskFormat() {
        val q = AniobAskUser.parse("ASK_USER: Which tier? | Cheapest | Fastest")
        assertNotNull(q)
        assertEquals("Which tier?", q!!.question)
        assertEquals(listOf("Cheapest", "Fastest"), q.options)
        assertNull(AniobAskUser.parse("Could not tap Send"))
    }

    @Test
    fun midRunQuestionWithOptionsContinuesOnTheRecommendedFirstOne() {
        val q = AniobAskUser.parse("ASK_USER: Which tier? | Cheapest | Fastest")!!
        val d = AniobAskUser.resolveWithoutUser(q, alreadyAssumed = false)
        assertEquals(MidRunDecision.Assume(q, "Cheapest"), d)
    }

    @Test
    fun valuesOnlyTheUserKnowsEndTheRunHonestly() {
        val q = AniobAskUser.parse("ASK_USER: Who should I message?")!!
        val d = AniobAskUser.resolveWithoutUser(q, alreadyAssumed = false)
        assertTrue(d is MidRunDecision.CannotContinue)
        assertTrue((d as MidRunDecision.CannotContinue).message.contains("Who should I message?"))
    }

    @Test
    fun neverAssumesConsentLikeAnswers() {
        for (text in listOf(
            "ASK_USER: Pay now? | Yes | No",
            "ASK_USER: Delete the file? | Yes | No",
            "ASK_USER: Enter your password? | Yes | No"
        )) {
            val d = AniobAskUser.resolveWithoutUser(AniobAskUser.parse(text)!!, alreadyAssumed = false)
            assertTrue(text, d is MidRunDecision.CannotContinue)
        }
    }

    @Test
    fun aSecondQuestionInTheSameRunEndsIt() {
        val q = AniobAskUser.parse("ASK_USER: Which tier? | Cheapest | Fastest")!!
        assertTrue(AniobAskUser.resolveWithoutUser(q, alreadyAssumed = true) is MidRunDecision.CannotContinue)
    }
}
