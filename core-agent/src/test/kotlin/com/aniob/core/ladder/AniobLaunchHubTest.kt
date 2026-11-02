package com.aniob.core.ladder

import com.aniob.core.domain.AniobScreenState
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Launch planning is the policy that decides *which* app opens and *when*. Every rule here is a
 * defect that was observed on a device, so each one is pinned:
 *
 *  - a mention inside a sentence never launches;
 *  - a sub-screen ("Open Wi-Fi settings") is not the Settings app;
 *  - the longest name wins, and a tie is a question, not a coin flip;
 *  - an app the platform says is not launchable is never returned;
 *  - the per-task memory stops a multi-step goal from re-launching its first app.
 */
class AniobLaunchHubTest {

    private val registry get() = AniobAppRegistry.getInstance()

    private val installed = listOf(
        RegisteredApp(
            packageName = "com.google.android.deskclock",
            label = "Clock",
            isSystemApp = true,
            launchActivity = "com.google.android.deskclock/com.android.deskclock.DeskClock"
        ),
        RegisteredApp("com.instagram.android", label = "Instagram"),
        RegisteredApp("com.android.settings", label = "Settings", isSystemApp = true),
        RegisteredApp("com.example.settings", label = "Settings Hub"),
        RegisteredApp("com.android.chrome", label = "Chrome"),
        RegisteredApp("com.android.contacts", label = "Contacts")
    )

    private val everythingLaunchable: (String) -> Boolean = { true }

    @Before
    fun setUp() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
        registry.hydrate(installed, mapOf(AniobAppRegistry.ROLE_CLOCK to "com.google.android.deskclock"))
    }

    @After
    fun tearDown() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
    }

    // ------------------------------------------------------------------ //
    // step-0 planning
    // ------------------------------------------------------------------ //

    @Test
    fun `a verb-led prompt plans the named app, package and component`() {
        val plan = AniobLaunchHub.plan(
            "Open Clock and set an alarm for 7:30 AM",
            registry,
            everythingLaunchable
        )
        assertEquals("com.google.android.deskclock", plan?.packageName)
        assertEquals("Clock", plan?.label)
        assertEquals(
            "com.google.android.deskclock/com.android.deskclock.DeskClock",
            plan?.launchActivity
        )
        assertEquals(LaunchPlan.SOURCE_REGISTRY, plan?.source)
    }

    @Test
    fun `the article and politeness prefixes are stripped`() {
        assertEquals("com.android.chrome", AniobLaunchHub.plan("please open the Chrome browser", registry)?.packageName)
        assertEquals("com.google.android.deskclock", AniobLaunchHub.plan("launch my Clock", registry)?.packageName)
        assertEquals("com.instagram.android", AniobLaunchHub.plan("switch to Instagram", registry)?.packageName)
        assertEquals("com.instagram.android", AniobLaunchHub.plan("go to Instagram", registry)?.packageName)
    }

    @Test
    fun `no verb means no launch`() {
        assertNull(AniobLaunchHub.plan("Set an alarm in Clock for 7 am", registry))
        assertNull(AniobLaunchHub.plan("What is the Clock app", registry))
        assertNull(AniobLaunchHub.plan("", registry))
        assertNull(AniobLaunchHub.plan("Open", registry))
    }

    @Test
    fun `a sub-screen name is not an app`() {
        // This is the bug the word-boundary check exists for: "wi-fi settings" is a Settings
        // *screen*, and opening the Settings app instead was wrong.
        assertNull(AniobLaunchHub.plan("Open Wi-Fi settings", registry))
        assertNull(AniobLaunchHub.plan("open battery saver", registry))
    }

    @Test
    fun `the longest matching name wins`() {
        assertEquals("com.example.settings", AniobLaunchHub.plan("Open Settings Hub and tap About", registry)?.packageName)
        assertEquals("com.android.settings", AniobLaunchHub.plan("Open Settings", registry)?.packageName)
    }

    @Test
    fun `two apps with the same name are ambiguous, not a guess`() {
        registry.hydrate(installed + RegisteredApp("com.other.clock", "Clock"), emptyMap())
        assertNull("an equal-length tie is an answer of 'ask', never a coin flip",
            AniobLaunchHub.plan("open clock", registry))
    }

    @Test
    fun `an app the platform rejects is never planned`() {
        assertNull(AniobLaunchHub.plan("open clock", registry) { false })
        assertNull(AniobLaunchHub.plan("open whatsapp", registry) { false })
        // A rejected live entry must not silently fall through to a stock seed with the same name.
        assertNull(AniobLaunchHub.plan("open instagram", registry) { false })
    }

    @Test
    fun `single-token typos resolve through the registry`() {
        assertEquals("com.instagram.android", AniobLaunchHub.plan("open instgram", registry)?.packageName)
        assertEquals("com.android.chrome", AniobLaunchHub.plan("open chorme", registry)?.packageName)
        assertNull(AniobLaunchHub.plan("open zephyr", registry))
    }

    // ------------------------------------------------------------------ //
    // cold registry
    // ------------------------------------------------------------------ //

    @Test
    fun `a cold registry falls back to stock package ids`() {
        AniobAppRegistry.resetForTest()
        val plan = AniobLaunchHub.plan("open whatsapp", AniobAppRegistry.getInstance())
        assertEquals("com.whatsapp", plan?.packageName)
        assertEquals(LaunchPlan.SOURCE_SEED, plan?.source)
        assertNull(AniobLaunchHub.plan("open zephyr", AniobAppRegistry.getInstance()))
    }

    // ------------------------------------------------------------------ //
    // mid-task planning
    // ------------------------------------------------------------------ //

    @Test
    fun `a mid-task switch plans the app named later in the task`() {
        val plan = AniobLaunchHub.planMidTask(
            prompt = "open clock, set an alarm, then open instagram",
            registry = registry
        )
        assertEquals("com.instagram.android", plan?.packageName)
    }

    @Test
    fun `mid-task planning skips apps this task already opened and the app in front`() {
        val prompt = "open clock, set an alarm, then open instagram"
        assertEquals(
            "com.google.android.deskclock",
            AniobLaunchHub.planMidTask(prompt, registry, exclude = setOf("com.instagram.android"))?.packageName
        )
        assertEquals(
            "com.google.android.deskclock",
            AniobLaunchHub.planMidTask(prompt, registry, currentPackage = "com.instagram.android")?.packageName
        )
        assertNull(
            AniobLaunchHub.planMidTask(
                prompt,
                registry,
                exclude = setOf("com.instagram.android", "com.google.android.deskclock")
            )
        )
    }

    @Test
    fun `mid-task planning never invents an app`() {
        assertNull(AniobLaunchHub.planMidTask("check the weather forecast", registry))
        assertNull(AniobLaunchHub.planMidTask("", registry))
    }

    // ------------------------------------------------------------------ //
    // per-task memory
    // ------------------------------------------------------------------ //

    @Test
    fun `per-task memory resets only when the task changes`() {
        AniobLaunchHub.reset("task-a")
        AniobLaunchHub.noteLaunched("com.instagram.android")
        assertTrue(AniobLaunchHub.wasLaunched("com.instagram.android"))
        assertEquals(setOf("com.instagram.android"), AniobLaunchHub.launchedPackages())

        // The router calls reset() on every planning round; the same task must keep its memory.
        AniobLaunchHub.reset("task-a")
        assertTrue(AniobLaunchHub.wasLaunched("com.instagram.android"))

        AniobLaunchHub.reset("task-b")
        assertFalse(AniobLaunchHub.wasLaunched("com.instagram.android"))
        assertTrue(AniobLaunchHub.launchedPackages().isEmpty())
    }

    // ------------------------------------------------------------------ //
    // verification & helpers
    // ------------------------------------------------------------------ //

    @Test
    fun `a launch is verified only against the foreground package`() {
        assertTrue(AniobLaunchHub.verifyForeground("com.android.chrome", "com.android.chrome"))
        assertTrue(AniobLaunchHub.verifyForeground("com.android.chrome", "COM.ANDROID.CHROME"))
        assertFalse(AniobLaunchHub.verifyForeground("com.android.chrome", "com.android.settings"))
        assertFalse(AniobLaunchHub.verifyForeground("com.android.chrome", null))
        assertFalse(AniobLaunchHub.verifyForeground("com.android.chrome", ""))
        assertTrue(
            AniobLaunchHub.verifyForeground(
                "com.android.chrome",
                AniobScreenState(packageName = "com.android.chrome")
            )
        )
    }

    @Test
    fun `the trailing clause is stripped without touching the name`() {
        assertEquals("Clock", AniobLaunchHub.stripTrailingClause("Clock and set an alarm for 7:30 AM"))
        assertEquals("Calculator", AniobLaunchHub.stripTrailingClause("Calculator, then add 2+2"))
        assertEquals("Chrome browser", AniobLaunchHub.stripTrailingClause("Chrome browser"))
        assertEquals("Settings Hub", AniobLaunchHub.stripTrailingClause("Settings Hub then tap About"))
    }

    @Test
    fun `an explicit component is reduced to the class name and described`() {
        assertEquals(
            "com.android.deskclock.DeskClock",
            AniobLaunchHub.activityClassName(
                "com.google.android.deskclock/com.android.deskclock.DeskClock"
            )
        )
        assertNull(AniobLaunchHub.activityClassName(null))
        assertNull(AniobLaunchHub.activityClassName(""))

        val plan = AniobLaunchHub.plan("open clock", registry)
        assertTrue(AniobLaunchHub.describe(plan).contains("com.google.android.deskclock"))
        assertTrue(AniobLaunchHub.describe(null).isNotBlank())
    }
}
