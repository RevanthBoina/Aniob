package com.aniob.core.policy

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobObservationPolicyTest {

    private fun tap(id: Int) = AniobAction.Tap(SemanticTarget.SomIndex(id))

    @Test
    fun `empty history forces observation`() {
        assertTrue(AniobObservationPolicy().shouldObserve())
    }

    @Test
    fun `consistent verified actions allow planning snapshot reuse`() {
        val policy = AniobObservationPolicy(maxBurstSteps = 3)
        policy.recordActionOutcome(tap(1), "com.app", true)
        policy.recordActionOutcome(tap(2), "com.app", true)
        assertFalse(policy.shouldObserve())
    }

    @Test
    fun `repeated identical action after divergent window forces observation`() {
        val policy = AniobObservationPolicy(maxBurstSteps = 3)
        // Animation lag scenario: distinct taps, then the same tap repeats (screen didn't move).
        policy.recordActionOutcome(tap(1), "com.app", true)
        policy.recordActionOutcome(tap(2), "com.app", true)
        policy.recordActionOutcome(tap(2), "com.app", true)
        assertTrue(policy.shouldObserve())
    }

    @Test
    fun `verified failure forces observation`() {
        val policy = AniobObservationPolicy()
        policy.recordActionOutcome(tap(1), "com.app", false)
        policy.recordActionOutcome(tap(2), "com.app", false)
        assertTrue(policy.shouldObserve())
    }

    @Test
    fun `cross package forces observation`() {
        val policy = AniobObservationPolicy()
        policy.recordActionOutcome(tap(1), "com.a", true)
        policy.recordActionOutcome(tap(2), "com.b", true)
        assertTrue(policy.shouldObserve())
    }

    @Test
    fun `scroll forces observation`() {
        val policy = AniobObservationPolicy()
        policy.recordActionOutcome(tap(1), "com.app", true)
        policy.recordActionOutcome(AniobAction.Swipe(SwipeDirection.DOWN), "com.app", true)
        assertTrue(policy.shouldObserve())
    }
}