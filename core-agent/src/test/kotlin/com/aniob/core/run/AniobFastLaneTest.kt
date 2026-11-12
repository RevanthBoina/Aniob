package com.aniob.core.run

import com.aniob.core.domain.CriterionState
import com.aniob.core.domain.DeviceAssertion
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import com.aniob.core.ladder.AniobIntentResolver
import com.aniob.core.ladder.AniobLaunchHub
import com.aniob.core.ladder.ResolvedIntentShortcut
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fast lane skips Grill-Me, feasibility, the Doctor and the observed step loop, so the rule
 * for entering it is pinned here: only a request that the shortcut answers *completely*.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AniobFastLaneTest {

    private val registry get() = AniobAppRegistry.getInstance()
    private val clock = "com.google.android.deskclock"
    private val whatsapp = "com.whatsapp"

    @Before
    fun setUp() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
        registry.hydrate(
            listOf(
                RegisteredApp(clock, label = "Clock", isSystemApp = true),
                RegisteredApp(whatsapp, label = "WhatsApp"),
                RegisteredApp("com.instagram.android", label = "Instagram"),
                RegisteredApp("com.android.chrome", label = "Chrome")
            )
        )
    }

    @After
    fun tearDown() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
    }

    /** The shortcut the resolver returns for any prompt that starts by opening [pkg]. */
    private fun launchShortcut(pkg: String) = ResolvedIntentShortcut(
        action = AniobIntentResolver.ACTION_MAIN,
        targetPackage = pkg,
        category = "android.intent.category.LAUNCHER",
        requiresObservation = true
    )

    private val reflex = ResolvedIntentShortcut(action = "aniob.test.REFLEX")

    private fun lane(prompt: String, pkg: String, accessibility: Boolean = true, gated: Boolean = false): RunLane =
        AniobFastLane.decide(prompt, launchShortcut(pkg), accessibility, gated, registry)

    // ------------------------------------------------------------------ //
    // lane selection
    // ------------------------------------------------------------------ //

    @Test
    fun `a bare app launch takes the fast launch lane`() {
        assertEquals(RunLane.FAST_LAUNCH, lane("Open Clock", clock))
        assertEquals(RunLane.FAST_LAUNCH, lane("please launch the Instagram app.", "com.instagram.android"))
        assertEquals(RunLane.FAST_LAUNCH, lane("open $clock", clock))
    }

    @Test
    fun `the resolver's own shortcut for a bare launch is fast-lane eligible`() {
        val resolved = AniobIntentResolver.resolve("Open Clock")
        assertNotNull(resolved)
        assertEquals(clock, resolved!!.targetPackage)
        assertEquals(RunLane.FAST_LAUNCH, AniobFastLane.decide("Open Clock", resolved, true, false, registry))
    }

    @Test
    fun `a launch that is only the first hop of a longer goal stays on the standard lane`() {
        // The resolver returns the app shortcut for these too (the task does start by opening
        // the app) - which is exactly why the lane decision cannot be "shortcut != null".
        listOf(
            "Open WhatsApp and send hi to Mom" to whatsapp,
            "open clock then check the world clock" to clock,
            "Open Instagram, like the first post" to "com.instagram.android",
            "open whatsapp chat with mom" to whatsapp
        ).forEach { (prompt, pkg) ->
            assertEquals("'$prompt' must keep its gates", RunLane.STANDARD, lane(prompt, pkg))
        }
    }

    @Test
    fun `sole launch is decided on the words after the launch verb`() {
        assertTrue(AniobLaunchHub.isSoleLaunch("open clock", clock, registry))
        assertTrue(AniobLaunchHub.isSoleLaunch("Launch my WhatsApp application!", whatsapp, registry))
        assertFalse(AniobLaunchHub.isSoleLaunch("open clock and set an alarm", clock, registry))
        assertFalse(AniobLaunchHub.isSoleLaunch("open clock", whatsapp, registry))
        assertFalse(AniobLaunchHub.isSoleLaunch("what is a clock", clock, registry))
    }

    @Test
    fun `a launch cannot be confirmed without accessibility so it keeps the standard lane`() {
        assertEquals(RunLane.STANDARD, lane("Open Clock", clock, accessibility = false))
    }

    @Test
    fun `a reflex whose dispatch is the outcome takes the fast reflex lane without accessibility`() {
        assertTrue(reflex.completesOnDispatch)
        assertEquals(RunLane.FAST_REFLEX, AniobFastLane.decide("turn on flashlight", reflex, false, false, registry))
        assertTrue(RunLane.FAST_REFLEX.isFast)
        assertTrue(RunLane.FAST_LAUNCH.isFast)
        assertFalse(RunLane.STANDARD.isFast)
    }

    @Test
    fun `no shortcut or a system-gated plan step means the standard lane`() {
        assertEquals(RunLane.STANDARD, AniobFastLane.decide("book me a cab to the airport", null, true, false, registry))
        assertEquals(RunLane.STANDARD, lane("Open Clock", clock, gated = true))
        assertEquals(RunLane.STANDARD, AniobFastLane.decide("turn on flashlight", reflex, true, true, registry))
    }

    @Test
    fun `an observed shortcut that is not a plain app launch stays on the standard lane`() {
        val alarms = ResolvedIntentShortcut(action = "android.intent.action.SHOW_ALARMS", requiresObservation = true)
        assertEquals(RunLane.STANDARD, AniobFastLane.decide("show alarms", alarms, true, false, registry))
        val editor = ResolvedIntentShortcut(
            action = AniobIntentResolver.ACTION_MAIN,
            targetPackage = clock,
            extras = mapOf("android.intent.extra.alarm_editor" to "true"),
            requiresObservation = true
        )
        assertEquals(RunLane.STANDARD, AniobFastLane.decide("open clock", editor, true, false, registry))
        val editorOnly = ResolvedIntentShortcut(action = "android.intent.action.SET_ALARM", opensEditorOnly = true)
        assertEquals(RunLane.STANDARD, AniobFastLane.decide("set alarm", editorOnly, true, false, registry))
    }

    @Test
    fun `fast lane tasks reach dispatch without entering the pre-execution gates`() = runTest {
        var gateRuns = 0
        val gates: suspend () -> Boolean = { gateRuns++; false }
        // Even gates that would refuse the task are never consulted on the fast lane.
        assertTrue(AniobFastLane.admit(lane("Open Clock", clock), gates))
        assertTrue(AniobFastLane.admit(RunLane.FAST_REFLEX, gates))
        assertEquals("understanding / feasibility / Grill-Me / Doctor must not run", 0, gateRuns)
    }

    @Test
    fun `standard lane tasks are admitted only by their gates`() = runTest {
        var gateRuns = 0
        assertFalse(AniobFastLane.admit(RunLane.STANDARD) { gateRuns++; false })
        assertTrue(AniobFastLane.admit(RunLane.STANDARD) { gateRuns++; true })
        assertEquals(2, gateRuns)
    }

    // ------------------------------------------------------------------ //
    // event-driven launch confirmation
    // ------------------------------------------------------------------ //

    @Test
    fun `launch confirmation returns at the first foreground match`() = runTest {
        var polls = 0
        val confirmed = AniobFastLane.confirmLaunched(
            target = clock,
            now = { currentTime },
            foreground = { if (++polls >= 3) clock else "com.aniob.app" }
        )
        assertTrue(confirmed)
        assertEquals(3, polls)
        // Two polls missed, 40 ms apart: nowhere near the 1500 ms deadline.
        assertEquals(2 * AniobFastLane.LAUNCH_CONFIRM_POLL_MS, currentTime)
    }

    @Test
    fun `launch confirmation is immediate when the target is already in front`() = runTest {
        assertTrue(AniobFastLane.confirmLaunched(clock, now = { currentTime }, foreground = { clock }))
        assertEquals(0L, currentTime)
    }

    @Test
    fun `launch confirmation is bounded by the deadline`() = runTest {
        var polls = 0
        val confirmed = AniobFastLane.confirmLaunched(
            target = clock,
            deadlineMs = 200L,
            now = { currentTime },
            foreground = { polls++; "com.android.launcher" }
        )
        assertFalse(confirmed)
        assertEquals(200L, currentTime)
        assertEquals(6, polls) // t = 0, 40, 80, 120, 160, 200
    }

    @Test
    fun `a foreground probe that throws or returns null is a miss not a crash`() = runTest {
        var polls = 0
        val confirmed = AniobFastLane.confirmLaunched(
            target = clock,
            deadlineMs = 120L,
            now = { currentTime },
            foreground = {
                when (++polls) {
                    1 -> throw IllegalStateException("service rebinding")
                    2 -> null
                    else -> clock
                }
            }
        )
        assertTrue(confirmed)
        assertEquals(3, polls)
    }

    // ------------------------------------------------------------------ //
    // closing verdict
    // ------------------------------------------------------------------ //

    @Test
    fun `a confirmed launch closes on package evidence`() {
        val bare = AniobFastLane.launchVerdict(null, clock)
        assertNotNull(bare)
        assertTrue(bare!!.all { it.state == CriterionState.PASSED && it.detail.isNotBlank() })

        val withPackage = AniobFastLane.launchVerdict(SuccessCriteria(mustShowPackage = listOf(clock), minSteps = 1), clock)
        assertNotNull(withPackage)
        assertTrue(withPackage!!.all { it.state == CriterionState.PASSED })
        assertEquals(1, withPackage.count { it.description == "Package '$clock'" })
    }

    @Test
    fun `criteria the foreground package cannot prove fall back to full observation`() {
        assertNull(AniobFastLane.launchVerdict(SuccessCriteria(mustContainText = listOf("Alarm")), clock))
        assertNull(AniobFastLane.launchVerdict(SuccessCriteria(mustShowPackage = listOf(whatsapp)), clock))
        assertNull(AniobFastLane.launchVerdict(SuccessCriteria(mustShowActivity = listOf("DeskClock")), clock))
        assertNull(AniobFastLane.launchVerdict(SuccessCriteria(deviceAsserts = listOf(DeviceAssertion.TORCH_ON)), clock))
        assertNull(AniobFastLane.launchVerdict(SuccessCriteria(mustShowPackage = listOf(clock), minSteps = 3), clock))
    }
}
