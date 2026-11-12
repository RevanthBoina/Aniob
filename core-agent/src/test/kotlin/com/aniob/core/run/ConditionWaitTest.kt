package com.aniob.core.run

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConditionWaitTest {

    @Test
    fun `until returns as soon as the condition holds`() = runTest {
        val met = ConditionWait.until(deadlineMs = 600L, now = { currentTime }) { currentTime >= 80L }
        assertTrue(met)
        assertEquals(80L, currentTime)
    }

    @Test
    fun `until costs nothing when the condition already holds`() = runTest {
        assertTrue(ConditionWait.until(deadlineMs = 800L, now = { currentTime }) { true })
        assertEquals(0L, currentTime)
    }

    @Test
    fun `until never waits longer than the fixed sleep it replaces`() = runTest {
        var checks = 0
        val met = ConditionWait.until(deadlineMs = 250L, stepMs = 50L, now = { currentTime }) { checks++; false }
        assertFalse(met)
        assertEquals(250L, currentTime)
        assertEquals(6, checks) // t = 0, 50, 100, 150, 200 and the final check at the deadline
    }

    @Test
    fun `until takes the final check at the deadline`() = runTest {
        val met = ConditionWait.until(deadlineMs = 100L, stepMs = 40L, now = { currentTime }) { currentTime >= 100L }
        assertTrue("the condition became true during the last, shortened step", met)
        assertEquals("the last step is cut to the deadline, not run past it", 100L, currentTime)
    }

    @Test
    fun `window quiet with required movement waits for events and then for silence`() = runTest {
        // Events at t = 40 and t = 80 (a scroll animation), nothing after.
        val sequence = { when { currentTime >= 80L -> 2L; currentTime >= 40L -> 1L; else -> 0L } }
        val quiet = ConditionWait.WindowQuiet(quietMs = 100L, requireMovement = true, now = { currentTime }, sequence = sequence)
        val met = ConditionWait.until(deadlineMs = 300L, now = { currentTime }) { quiet.settled() }
        assertTrue(met)
        // Last event at 80 ms + 100 ms of silence = 180 ms; the first poll past that is at 200 ms.
        assertEquals(200L, currentTime)
    }

    @Test
    fun `window quiet with required movement falls back to the deadline when nothing moves`() = runTest {
        val quiet = ConditionWait.WindowQuiet(requireMovement = true, now = { currentTime }, sequence = { 7L })
        assertFalse(ConditionWait.until(deadlineMs = 300L, now = { currentTime }) { quiet.settled() })
        assertEquals(300L, currentTime)
    }

    @Test
    fun `an idle screen settles after the quiet window without needing movement`() = runTest {
        val quiet = ConditionWait.WindowQuiet(quietMs = 100L, requireMovement = false, now = { currentTime }, sequence = { 7L })
        assertTrue(ConditionWait.until(deadlineMs = 250L, now = { currentTime }) { quiet.settled() })
        assertEquals(120L, currentTime) // first 40 ms poll at or past 100 ms of silence
    }

    @Test
    fun `a screen that keeps moving uses the whole budget`() = runTest {
        val quiet = ConditionWait.WindowQuiet(quietMs = 100L, requireMovement = false, now = { currentTime }, sequence = { currentTime })
        assertFalse(ConditionWait.until(deadlineMs = 250L, stepMs = 50L, now = { currentTime }) { quiet.settled() })
        assertEquals(250L, currentTime)
    }
}
