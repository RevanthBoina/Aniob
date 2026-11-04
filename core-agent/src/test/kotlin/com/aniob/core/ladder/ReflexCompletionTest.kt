package com.aniob.core.ladder

import com.aniob.core.knowledge.AniobAppRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Set alarm for 7pm` dispatched cleanly and was still recorded as a failure, because "nothing to
 * observe" was read as "not verified". [ResolvedIntentShortcut.completesOnDispatch] is the one
 * predicate the caller uses to tell a finished reflex from a partial one.
 */
class ReflexCompletionTest {

    @Before
    fun setUp() = AniobAppRegistry.resetForTest()

    @After
    fun tearDown() = AniobAppRegistry.resetForTest()

    private fun completes(prompt: String): Boolean {
        val shortcut = AniobIntentResolver.resolve(prompt)
        assertNotNull("'$prompt' should resolve to a shortcut", shortcut)
        return shortcut!!.completesOnDispatch
    }

    @Test
    fun `alarm and timer with their parameters complete on dispatch`() {
        assertTrue(completes("Set alarm for 7pm"))
        assertTrue(completes("set a timer for 10 minutes"))
    }

    @Test
    fun `torch battery dial and url reflexes complete on dispatch`() {
        assertTrue(completes("turn on flashlight"))
        assertTrue(completes("check battery"))
        assertTrue(completes("call 9876543210"))
        assertTrue(completes("open website example.org"))
    }

    @Test
    fun `an alarm with a second goal or a visible result is not complete`() {
        assertFalse(completes("set an alarm for 7:30 and open instagram"))
        assertFalse(completes("set an alarm for 7:30 leaving the alarm list showing 7:30 AM"))
    }

    @Test
    fun `an app launch is never complete on dispatch`() {
        assertFalse(completes("Open Settings"))
    }

    @Test
    fun `a bare set alarm with no clock app known only opens an editor`() {
        // Registry reset: no clock role, and the seed is bypassed for the implicit-intent branch.
        val shortcut = AniobIntentResolver.resolve("set alarm")
        assertNotNull(shortcut)
        if (shortcut!!.targetPackage == null) {
            assertFalse("the editor still needs the user", shortcut.completesOnDispatch)
        }
    }
}
