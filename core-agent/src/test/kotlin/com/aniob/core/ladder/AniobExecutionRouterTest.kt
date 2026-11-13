package com.aniob.core.ladder

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.knowledge.AppCatalogEntry
import com.aniob.core.tools.DevicePowerState
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobExecutionRouterTest {

    private val screen = AniobScreenState(
        packageName = "com.android.settings",
        activityName = "SettingsActivity",
        treeHash = "settings-hash-1"
    )
    private val power = DevicePowerState()

    /** Per-task launch memory is process-wide state; a fresh signature must not leak between tests. */
    @Before
    fun resetLaunchMemory() {
        AniobLaunchHub.clearMemory()
    }

    @Test fun `skill uses verified local cursor rather than global task step`() {
        val engine = com.aniob.core.skills.AniobSkillEngine()
        engine.registerSkill(com.aniob.core.skills.AniobSkill("cursor-test", description = "cursor",
            triggerKeywords = listOf("cursor task"), steps = listOf(
                com.aniob.core.skills.SkillStep(0, "wait", waitDuration = 20),
                com.aniob.core.skills.SkillStep(1, "wait", waitDuration = 40))))
        val router = AniobExecutionRouter(skillMatcher = com.aniob.core.skills.AniobSkillMatcher(engine))
        router.intentShortcutsEnabled = false
        router.fastPathEnabled = false
        fun plan(step: Int) = router.planStep("cursor task", screen, step, "cursor task", power)
        val first = plan(5) as AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution
        assertEquals(20L, (first.action as AniobAction.Wait).durationMs)
        val retry = plan(6) as AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution
        assertEquals(20L, (retry.action as AniobAction.Wait).durationMs)
        router.onSkillStepRecorded(true)
        val second = plan(7) as AniobExecutionRouter.ExecutionPlanResult.SkillStepExecution
        assertEquals(40L, (second.action as AniobAction.Wait).durationMs)
        router.onSkillStepRecorded(false)
        assertTrue(plan(8) is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    @Test
    fun `model dispatch carries condensed progress summary when planning agent present`() {
        val router = AniobExecutionRouter(planningAgent = AniobPlanningAgent())
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.

        // Isolate the model rung; automatic skills now recognize paraphrases at any task step.
        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        val dispatch = result as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch
        assertNotNull("Summary must be produced by the planning agent", dispatch.progressSummary)
        assertTrue(dispatch.progressSummary!!.contains("Task: Open wifi settings"))
        assertTrue(dispatch.progressSummary!!.contains("Completed 1"))
    }

    @Test
    fun `a parameterised shortcut is dispatched once per task signature, mid-task included`() {
        val router = AniobExecutionRouter()
        // Carries parameters (7:30) and needs observation, so the caller continues afterwards.
        val prompt = "open clock then set an alarm for 7:30"
        val signature = prompt.lowercase()

        // Step 1: the second hop of a multi-goal task, after the first hop already ran.
        val first = router.planStep(
            taskPrompt = prompt,
            screenState = screen,
            stepIndex = 1,
            taskSignature = signature,
            powerState = power
        )
        assertTrue("mid-task parameterised shortcut must dispatch", first is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        val shortcut = (first as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut
        assertEquals(AniobIntentResolver.ACTION_SET_ALARM, shortcut.action)
        assertTrue(shortcut.requiresObservation)

        // The next planning round of the same task must not re-fire it.
        val second = router.planStep(
            taskPrompt = prompt,
            screenState = screen,
            stepIndex = 2,
            taskSignature = signature,
            powerState = power
        )
        assertFalse(
            "the same shortcut must not be dispatched twice in one task",
            second is AniobExecutionRouter.ExecutionPlanResult.DirectIntent
        )

        // A new task starts with a clean slate.
        val otherTask = router.planStep(
            taskPrompt = prompt,
            screenState = screen,
            stepIndex = 1,
            taskSignature = "a different task",
            powerState = power
        )
        assertTrue(
            "dispatch memory is per task",
            otherTask is AniobExecutionRouter.ExecutionPlanResult.DirectIntent
        )
    }

    @Test
    fun `a silent shortcut still fires only at step 0`() {
        val router = AniobExecutionRouter()
        // WIFI_SETTINGS is silent: it finishes a run, so it may not fire mid-trajectory.
        val result = router.planStep(
            taskPrompt = "open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )
        assertFalse(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
    }

    @Test
    fun `router works with nullable planning agent`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        val dispatch = result as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch
        assertNull("No summary when planning agent is disabled", dispatch.progressSummary)
    }

    @Test
    fun `previous progress accumulates across consecutive model dispatches`() {
        val router = AniobExecutionRouter(planningAgent = AniobPlanningAgent())
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.

        val first = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 4,
            taskSignature = "open wifi settings",
            powerState = power
        ) as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch

        // Feed a prior TaskProgress with 1 completed op; the router should append its own
        // "Step 4 executed" operation → completed grows to 2.
        val second = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 5,
            taskSignature = "open wifi settings",
            powerState = power,
            previousProgress = TaskProgress(
                completed = listOf("Step 3 executed"),
                pending = listOf("Continue: Open wifi settings"),
                summary = first.progressSummary.orEmpty()
            )
        ) as AniobExecutionRouter.ExecutionPlanResult.ModelDispatch

        assertEquals("Task: Open wifi settings", second.progressSummary?.substringBefore(" |"))
        assertEquals(
            "Completed count must accumulate: 1 prior + 1 new = 2",
            2,
            second.progressSummary!!.substringAfter("Completed ").substringBefore(":").toInt()
        )
        assertTrue(second.progressSummary!!.contains("Step 3 executed"))
        assertTrue(second.progressSummary!!.contains("Step 4 executed"))
    }

    @Test
    fun `disabled fast path must not attempt cache lookup`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.fastPathEnabled = false

        // Negative control: when fastPathEnabled is false, lookup must immediately return null
        val lookupResult = router.lookup("any task signature")
        assertNull("Disabled fast path must skip cache lookup entirely", lookupResult)

        // When planStep runs with fastPathEnabled = false, it must never yield FastPathStep
        val plan = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )
        assertTrue("Must not return FastPathStep when fastPathEnabled = false", plan !is AniobExecutionRouter.ExecutionPlanResult.FastPathStep)
    }

    // ---------------------------------------------------------------------------------------------
    // Remember replay rung
    // ---------------------------------------------------------------------------------------------

    private val cachedTap = AniobAction.Tap(SemanticTarget.SomIndex(2))

    @Test
    fun `remember replay returns the cached actions and skips every model rung`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.rememberReplayEnabled = true
        var lookups = 0
        router.rememberLookup = { goal, seen ->
            lookups++
            assertEquals("Open wifi settings", goal)
            assertEquals("com.android.settings", seen.packageName)
            listOf(cachedTap)
        }

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.RememberReplay)
        assertEquals(listOf(cachedTap), (result as AniobExecutionRouter.ExecutionPlanResult.RememberReplay).actions)
        assertEquals(1, lookups)
    }

    @Test
    fun `remember replay is off unless the ablation condition enables it`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.
        var lookups = 0
        router.rememberLookup = { _, _ ->
            lookups++
            listOf(cachedTap)
        }

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue("the cache must not be consulted in the other conditions", result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        assertEquals(0, lookups)
    }

    @Test
    fun `a cache miss falls through to the normal model rung`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.
        router.rememberReplayEnabled = true
        router.rememberLookup = { _, _ -> null }

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    @Test
    fun `an empty cache result is a miss, not an empty replay`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.skillsEnabled = false // Isolate model/cache behavior from automatic skill selection.
        router.rememberReplayEnabled = true
        router.rememberLookup = { _, _ -> emptyList() }

        val result = router.planStep(
            taskPrompt = "Open wifi settings",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    @Test
    fun `the intent shortcut is resolved before the remember cache`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.rememberReplayEnabled = true
        var lookups = 0
        router.rememberLookup = { _, _ ->
            lookups++
            listOf(cachedTap)
        }

        val result = router.planStep(
            // A *reflex* is what may finish immediately. "open settings" is an app launch, which
            // must be observed first, so it is no longer a DirectIntent (see AniobMidTaskLaunchTest).
            taskPrompt = "turn on flashlight",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "turn on flashlight",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        assertEquals("the zero-millisecond intent rung stays first", 0, lookups)
    }

    // ---------------------------------------------------------------------------------------------
    // Step-0 app launch
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `app launch turns "open app and do x" into a zero-model first action`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { goal ->
            if (goal.startsWith("Open Contacts")) AppLaunchTarget("com.android.contacts", "Contacts") else null
        }

        val result = router.planStep(
            taskPrompt = "Open Contacts and search for John",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open contacts and search for john",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
        val launch = result as AniobExecutionRouter.ExecutionPlanResult.AppLaunch
        assertEquals("com.android.contacts", launch.packageName)
        assertEquals("Contacts", launch.appName)
    }

    @Test
    fun `app launch is gated by the shortcut switch`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = false
        var calls = 0
        router.appLaunchResolver = {
            calls++
            AppLaunchTarget("com.google.android.deskclock", "Clock")
        }

        val result = router.planStep(
            taskPrompt = "Open Clock and set an alarm",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open clock",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
        assertEquals("the resolver must not even be consulted", 0, calls)
    }

    @Test
    fun `app launch only applies to the first step`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.appLaunchResolver = { AppLaunchTarget("com.google.android.deskclock", "Clock") }

        val result = router.planStep(
            taskPrompt = "Open Clock and set an alarm",
            screenState = screen,
            stepIndex = 1,
            taskSignature = "open clock",
            powerState = power
        )

        assertTrue(result !is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
    }

    @Test
    fun `an alarm prompt becomes a complete direct intent, ahead of the app launch`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { AppLaunchTarget("com.google.android.deskclock", "Clock") }

        val result = router.planStep(
            taskPrompt = "Open Clock and set an alarm for 7:30 AM",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open clock and set an alarm",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        val shortcut = (result as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut
        assertEquals("android.intent.action.SET_ALARM", shortcut.action)
        assertTrue(
            "a two-goal prompt is not finished by the alarm half",
            shortcut.requiresObservation
        )
    }

    @Test
    fun `an alarm the prompt wants to see is dispatched and then observed`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { AppLaunchTarget("com.google.android.deskclock", "Clock") }

        val result = router.planStep(
            taskPrompt = "Open Clock and set an alarm for 7:30 AM, leaving the alarm list showing \"7:30 AM\".",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open clock and set an alarm for 7:30 am",
            powerState = power
        )

        // The intent still opens the editor with the time filled in, but the run continues so the
        // agent can press Save and show the alarm list the prompt asked for.
        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        val shortcut = (result as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut
        assertEquals("android.intent.action.SET_ALARM", shortcut.action)
        assertEquals("7", shortcut.extras[AniobIntentResolver.EXTRA_ALARM_HOUR])
        assertTrue(shortcut.requiresObservation)
    }

    @Test
    fun `a single goal alarm still completes from the dispatch alone`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true

        val result = router.planStep(
            taskPrompt = "set an alarm for 7:30 AM",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "set an alarm for 7:30 am",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        val shortcut = (result as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut
        assertEquals("android.intent.action.SET_ALARM", shortcut.action)
        assertEquals("true", shortcut.extras[AniobIntentResolver.EXTRA_ALARM_SKIP_UI])
        assertFalse("one goal, fully answered by the intent", shortcut.requiresObservation)
    }

    @Test
    fun `a settings panel prompt is resolved before any model call`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true

        val result = router.planStep(
            taskPrompt = "Open Wi-Fi settings",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open wifi settings",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.DirectIntent)
        assertEquals(
            "android.settings.WIFI_SETTINGS",
            (result as AniobExecutionRouter.ExecutionPlanResult.DirectIntent).shortcut.action
        )
    }

    @Test
    fun `an unresolved prompt falls through to the model`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.appLaunchResolver = { null }

        val result = router.planStep(
            taskPrompt = "Book a cab to downtown",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "book a cab to downtown",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    @Test
    fun `a named app in a longer task becomes an observable app launch, not a finished intent`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.intentShortcutsEnabled = true
        router.appLaunchResolver = { null }

        val result = router.planStep(
            taskPrompt = "Open the camera and take a photo",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open the camera and take a photo",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.AppLaunch)
    }

    @Test
    fun `a throwing resolver never breaks planning`() {
        val router = AniobExecutionRouter(planningAgent = null)
        router.appLaunchResolver = { error("catalog exploded") }

        val result = router.planStep(
            taskPrompt = "Open Contacts and search for John",
            screenState = screen,
            stepIndex = 0,
            taskSignature = "open contacts",
            powerState = power
        )

        assertTrue(result is AniobExecutionRouter.ExecutionPlanResult.ModelDispatch)
    }

    // --- step-0 app shortcut ---------------------------------------------------------
    private val catalog = listOf(
        AppCatalogEntry(packageName = "com.google.android.deskclock", appName = "Clock", aliases = listOf("alarm")),
        AppCatalogEntry(packageName = "com.android.contacts", appName = "Contacts"),
        AppCatalogEntry(packageName = "com.android.settings", appName = "Settings"),
        AppCatalogEntry(packageName = "com.google.android.calculator", appName = "Calculator"),
        AppCatalogEntry(packageName = "com.android.chrome", appName = "Chrome"),
        AppCatalogEntry(packageName = "com.google.android.dialer", appName = "Phone"),
        AppCatalogEntry(packageName = "com.example.settings", appName = "Settings Hub")
    )

    @Test
    fun resolvesTheAppNamedAtTheStartOfAnImperative() {
        assertEquals(
            "com.google.android.deskclock",
            AniobAppLaunchResolver.resolve("Open Clock and set an alarm for 7:30 AM", catalog)?.packageName
        )
        assertEquals(
            "com.android.contacts",
            AniobAppLaunchResolver.resolve("Open Contacts and search for John", catalog)?.packageName
        )
        assertEquals(
            "com.google.android.calculator",
            AniobAppLaunchResolver.resolve("Launch Calculator", catalog)?.packageName
        )
    }

    @Test
    fun theLongestMatchingNameWins() {
        // "Settings Hub" and "Settings" both prefix-match; the more specific name is chosen.
        val target = AniobAppLaunchResolver.resolve("Open Settings Hub and tap About", catalog)
        assertEquals("com.example.settings", target?.packageName)
    }

    @Test
    fun aMentionInsideASentenceNeverLaunches() {
        assertNull(AniobAppLaunchResolver.resolve("Set an alarm in Clock for 7 am", catalog))
        assertNull(AniobAppLaunchResolver.resolve("What is the Clock app", catalog))
    }

    @Test
    fun aSubScreenNameDoesNotMatchAnApp() {
        // "Wi-Fi settings" must not be read as the Settings app: the name is checked as a prefix.
        assertNull(AniobAppLaunchResolver.resolve("Open Wi-Fi settings", catalog))
    }

    @Test
    fun thePolitenessPrefixIsTolerated() {
        assertEquals(
            "com.android.chrome",
            AniobAppLaunchResolver.resolve("please open the Chrome browser", catalog)?.packageName
        )
    }

    @Test
    fun anUninstalledAppIsNotLaunched() {
        assertNull(
            AniobAppLaunchResolver.resolve(
                prompt = "Open Clock and set an alarm",
                entries = catalog,
                isInstalled = { false }
            )
        )
    }

    @Test
    fun anEmptyCatalogOrPromptResolvesNothing() {
        assertNull(AniobAppLaunchResolver.resolve("Open Clock", emptyList()))
        assertNull(AniobAppLaunchResolver.resolve("", catalog))
        assertNull(AniobAppLaunchResolver.resolve("Open", catalog))
    }

    @Test
    fun aliasesResolveToTheirApp() {
        assertEquals(
            "com.google.android.deskclock",
            AniobAppLaunchResolver.resolve("Open the alarm app", catalog)?.packageName
        )
    }
}
