package com.aniob.core.ladder

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Field failures this pins (Galaxy M34, all of them went to a model that was down):
 *  - `open linkedin in chrome`
 *  - `open linkedin` with no LinkedIn app
 *  - `Search in YouTube`
 */
class AniobWebLaunchPlannerTest {

    @Before
    fun setUp() {
        AniobLaunchHub.clearMemory()
        AniobAppRegistry.resetForTest()
        AniobAppRegistry.getInstance().hydrate(
            listOf(
                RegisteredApp("com.android.chrome", "Chrome", aliases = listOf("chrome")),
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

    @Test
    fun `open linkedin in chrome is a URL intent aimed at chrome`() {
        val plan = AniobLaunchHub.planWeb("open linkedin in chrome")
        assertNotNull(plan)
        assertEquals("https://www.linkedin.com", plan!!.url)
        assertEquals("com.android.chrome", plan.browserPackage)
        assertEquals(WebLaunchPlan.SOURCE_COMPOUND, plan.source)
    }

    @Test
    fun `the resolver turns the compound prompt into a reflex that completes on dispatch`() {
        val shortcut = AniobIntentResolver.resolve("Open LinkedIn in Chrome")
        assertNotNull(shortcut)
        assertEquals(AniobIntentResolver.ACTION_VIEW, shortcut!!.action)
        assertEquals("https://www.linkedin.com", shortcut.uriString)
        assertEquals("com.android.chrome", shortcut.preferredPackage)
        assertNull("the browser is a preference, never a required app", shortcut.targetPackage)
        assertTrue(shortcut.completesOnDispatch)
    }

    @Test
    fun `an installed app named with a browser clause still opens the web page`() {
        val plan = AniobLaunchHub.planWeb("open instagram in chrome")
        assertEquals("https://www.instagram.com", plan?.url)
    }

    @Test
    fun `a domain is used as typed and a bare word gets a dot com only with a browser cue`() {
        assertEquals("https://example.org/path", AniobLaunchHub.planWeb("open example.org/path in browser")?.url)
        assertEquals("https://www.acmecorp.com", AniobLaunchHub.planWeb("open acmecorp in chrome")?.url)
        assertNull(AniobLaunchHub.planWeb("open acmecorp"))
    }

    @Test
    fun `browser falls back to the registry browser role or the system default`() {
        val plan = AniobLaunchHub.planWeb("open github in the browser")
        assertEquals("https://github.com", plan?.url)
        // No browser registered with the browser role here: the dispatcher uses the system default.
        assertNull(plan?.browserPackage)
    }

    @Test
    fun `a missing chrome falls back to the default browser instead of naming it`() {
        AniobAppRegistry.resetForTest()
        AniobAppRegistry.getInstance().hydrate(listOf(RegisteredApp("com.instagram.android", "Instagram")))
        val plan = AniobLaunchHub.planWeb("open linkedin in chrome")
        assertEquals("https://www.linkedin.com", plan?.url)
        assertNull(plan?.browserPackage)
    }

    @Test
    fun `open linkedin with no linkedin app falls back to the web page`() {
        val plan = AniobLaunchHub.planWeb("open linkedin")
        assertEquals("https://www.linkedin.com", plan?.url)
        assertEquals(WebLaunchPlan.SOURCE_APP_FALLBACK, plan?.source)

        val shortcut = AniobIntentResolver.resolve("open linkedin")
        assertEquals(AniobIntentResolver.ACTION_VIEW, shortcut?.action)
        assertEquals("https://www.linkedin.com", shortcut?.uriString)
        assertTrue(shortcut!!.completesOnDispatch)
    }

    @Test
    fun `an installed app always beats the web fallback`() {
        assertNull(AniobLaunchHub.planWeb("open instagram"))
        val shortcut = AniobIntentResolver.resolve("open instagram")
        assertEquals(AniobIntentResolver.ACTION_MAIN, shortcut?.action)
        assertEquals("com.instagram.android", shortcut?.targetPackage)
        assertTrue(shortcut!!.requiresObservation)
    }

    @Test
    fun `a second goal or an unknown name is never answered with a website`() {
        assertNull(AniobLaunchHub.planWeb("open linkedin and message Sam"))
        assertNull(AniobLaunchHub.planWeb("open my resume from files"))
        assertNull(AniobLaunchHub.planWeb("open report.pdf"))
        assertNull(AniobLaunchHub.planWeb("open my resume in chrome"))
    }

    @Test
    fun `site scoped search resolves to the site search page`() {
        val withQuery = AniobLaunchHub.planWeb("search for lofi beats in youtube")
        assertEquals("https://www.youtube.com/results?search_query=lofi+beats", withQuery?.url)
        assertEquals(WebLaunchPlan.SOURCE_SEARCH, withQuery?.source)

        val bare = AniobLaunchHub.planWeb("Search in YouTube")
        assertEquals("https://www.youtube.com", bare?.url)
        assertFalse(AniobIntentResolver.resolve("Search in YouTube")?.requiresObservation ?: true)

        val inverted = AniobLaunchHub.planWeb("Search in YouTube for cat videos")
        assertEquals("https://www.youtube.com/results?search_query=cat+videos", inverted?.url)
        assertEquals(WebLaunchPlan.SOURCE_SEARCH, inverted?.source)

        val direct = AniobLaunchHub.planWeb("search youtube for cat videos")
        assertEquals("https://www.youtube.com/results?search_query=cat+videos", direct?.url)
    }

    @Test
    fun `search with a second clause or an unknown site is left to the planner`() {
        assertNull(AniobLaunchHub.planWeb("search for cats on youtube and play the first video"))
        assertNull(AniobLaunchHub.planWeb("search for cats in my notes"))
    }
}
