package com.aniob.core.ladder

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import com.aniob.core.tools.DevicePowerState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mid-task app switching, end to end through [AniobExecutionRouter].
 *
 * The requirement is narrow and easy to get wrong: a task may open a second app part-way through
 * ("… then open Instagram") **once**, it must never re-open the app it started in, and it must not
 * touch an app that is already in front.
 */
class AniobMidTaskLaunchTest {

    private val power = DevicePowerState()
    private val clockScreen = AniobScreenState(packageName = "com.google.android.deskclock")
    private val homeScreen = AniobScreenState(packageName = "com.android.launcher3")

    private val clock = AppLaunchTarget("com.google.android.deskclock", "Clock")
    private val instagram = AppLaunchTarget("com.instagram.android", "Instagram")

    @Before
    fun setUp() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
        AniobAppRegistry.getInstance().hydrate(
            listOf(
                RegisteredApp("com.google.android.deskclock", "Clock", isSystemApp = true),
                RegisteredApp("com.instagram.android", "Instagram")
            )
        )
    }

    @After
    fun tearDown() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
    }

    private fun plan(router: AniobExecutionRouter, screen: AniobScreenState, step: Int, prompt: String) =
        router.planStep(
            taskPrompt = prompt,
            screenState = screen,
            stepIndex = step,
            taskSignature = prompt.lowercase(),
            powerState = power
        )

    @Test
    fun `a mid-task switch fires exactly once`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.midTaskLaunchResolver = { instagram }
        val prompt = "open clock, set an alarm, then open instagram"

        val first = plan(router, clockScreen, 1, prompt)
        assertTrue(first is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
        assertEquals("com.instagram.android", (first as AniobExecutionRouter.ExecutionPlanResult.AppLaunch).packageName)

        val second = plan(router, homeScreen, 2, prompt)
        assertFalse(
            "the app this task already switched to must not be opened again",
            second is AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        )
    }

    @Test
    fun `the app this task already opened is not re-launched`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { clock }
        // A resolver that does not filter by itself: the router's per-task memory must still hold.
        router.midTaskLaunchResolver = { clock }
        val prompt = "open clock and set an alarm"

        val step0 = plan(router, homeScreen, 0, prompt)
        assertTrue(step0 is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)

        // A different screen, so only the per-task memory (not the foreground check) can stop it.
        val step1 = plan(router, homeScreen, 1, prompt)
        assertFalse(
            "opening the starting app again is the loop this memory exists to stop",
            step1 is AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        )
    }

    @Test
    fun `an app already in front is skipped`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.midTaskLaunchResolver = { clock }

        val result = plan(router, clockScreen, 3, "open clock and set an alarm")
        assertFalse(
            "dispatching OpenApp for the foreground package is a no-op step",
            result is AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        )
    }

    @Test
    fun `without a mid-task resolver the ladder behaves as before`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.midTaskLaunchResolver = null

        val result = plan(router, clockScreen, 2, "open clock, set an alarm, then open instagram")
        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    @Test
    fun `step 0 still uses the app launch resolver and never the mid-task one`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        var midTaskCalls = 0
        router.appLaunchResolver = { clock }
        router.midTaskLaunchResolver = {
            midTaskCalls++
            instagram
        }

        val result = plan(router, homeScreen, 0, "open clock and set an alarm")
        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
        assertEquals("com.google.android.deskclock", (result as AniobExecutionRouter.ExecutionPlanResult.AppLaunch).packageName)
        assertEquals("the first step belongs to the step-0 resolver", 0, midTaskCalls)
    }

    @Test
    fun `the reason distinguishes a start from a switch`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { clock }
        router.midTaskLaunchResolver = { instagram }

        val start = plan(router, homeScreen, 0, "open clock and set an alarm") as
            AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        assertTrue(start.reason.contains("App shortcut"))

        val switch = plan(router, clockScreen, 1, "open clock and set an alarm") as
            AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        assertTrue(switch.reason.contains("App switch"))
    }

    @Test
    fun `a shortcut that opens an app is launched as an observed step, not a direct intent`() {
        // "set alarm" names the clock app through the intent resolver (no launch verb, so the
        // step-0 planner deliberately passes). It must become an OpenApp step that the verifier can
        // confirm, never a DirectIntent that finishes without a post-condition.
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { null }
        router.midTaskLaunchResolver = { null }

        val result = plan(router, homeScreen, 0, "set alarm")
        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
        val launch = result as AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        assertEquals("com.google.android.deskclock", launch.packageName)
        assertFalse(
            "an app launch must never be a DirectIntent",
            result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent
        )
    }

    @Test
    fun `pure reflexes still finish immediately`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true

        val torch = plan(router, homeScreen, 0, "turn on flashlight")
        assertTrue(torch is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        assertEquals(
            AniobIntentResolver.ACTION_FLASHLIGHT,
            (torch as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut.action
        )
    }
}
