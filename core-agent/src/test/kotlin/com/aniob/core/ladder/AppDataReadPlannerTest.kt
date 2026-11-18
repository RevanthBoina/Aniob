package com.aniob.core.ladder

import com.aniob.core.intent.AniobAppDataRead
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where an app-data read request has to be carried out.
 *
 * The defect these pin: "summarize the newest emails in Gmail" either went to a model (which
 * answered with prose and never opened anything) or to a browser tab even though Gmail was
 * installed. A destination must be a lookup against the installed-app registry, the platform's own
 * role, and — only when neither answers — the documented service page.
 */
class AppDataReadPlannerTest {

    /** A registry holding exactly [apps]: the singleton, cleared first so tests cannot leak. */
    private fun registry(vararg apps: RegisteredApp, roles: Map<String, String> = emptyMap()): AniobAppRegistry {
        val registry = AniobAppRegistry.getInstance()
        registry.clear()
        return registry.also { it.hydrate(apps.toList(), roles) }
    }

    private fun emptyRegistry(): AniobAppRegistry = AniobAppRegistry.getInstance().also { it.clear() }

    private val gmail = RegisteredApp(
        packageName = "com.google.android.gm",
        label = "Gmail",
        launchActivity = "com.google.android.gm/.ConversationListActivityGmail"
    )
    private val slack = RegisteredApp("com.Slack", "Slack")

    @Test
    fun `a named installed app is opened instead of a browser tab`() {
        val registry = registry(gmail)
        val plan = AniobAppDataReadPlanner.planForPrompt(
            "Summarize the newest emails in Gmail.", registry
        ) as AppDataReadPlan.Ready

        assertEquals("com.google.android.gm", plan.destination.packageName)
        assertNull("an installed app must win over the web page", plan.destination.webUrl)
        assertEquals(AppDataReadDestination.SOURCE_NAMED_APP, plan.destination.source)
    }

    @Test
    fun `the resolved destination keeps the exact launch component`() {
        val plan = AniobAppDataReadPlanner.planForPrompt("Read my gmail inbox", registry(gmail))
            as AppDataReadPlan.Ready
        assertEquals("com.google.android.gm/.ConversationListActivityGmail", plan.destination.launchActivity)
    }

    @Test
    fun `an uninstalled service falls back to its supported web page`() {
        val plan = AniobAppDataReadPlanner.planForPrompt(
            "Summarize the newest emails in Gmail.", registry(slack)
        ) as AppDataReadPlan.Ready

        assertNull("no installed app may be invented", plan.destination.packageName)
        assertEquals("https://mail.google.com", plan.destination.webUrl)
        assertEquals(AppDataReadDestination.SOURCE_WEB, plan.destination.source)
    }

    @Test
    fun `with no app named the platform role answers`() {
        val registry = registry(gmail, roles = mapOf("email" to gmail.packageName))
        val plan = AniobAppDataReadPlanner.planForPrompt("Check my two newest emails.", registry)
            as AppDataReadPlan.Ready

        assertEquals(gmail.packageName, plan.destination.packageName)
        assertEquals(AppDataReadDestination.SOURCE_ROLE, plan.destination.source)
    }

    @Test
    fun `an ambiguous name is a question, never a guess`() {
        val registry = registry(
            RegisteredApp("com.Slack", "Slack"),
            RegisteredApp("com.Slack.work", "Slack")
        )
        val plan = AniobAppDataReadPlanner.planForPrompt("Read my latest messages in Slack", registry)
        assertTrue("two apps answering the same name must be reported as ambiguous", plan is AppDataReadPlan.Ambiguous)
    }

    @Test
    fun `a cold registry does not turn a named app into a website`() {
        val plan = AniobAppDataReadPlanner.planForPrompt("Summarize the newest emails in Gmail.", emptyRegistry())
        assertTrue("an unhydrated registry must not be read as 'app not installed'", plan is AppDataReadPlan.Unresolved)
    }

    @Test
    fun `the intent resolver returns an observed launch, never a finished shortcut`() {
        // The resolver reads the process-wide registry, so this test hydrates it explicitly.
        val global = registry(gmail, roles = mapOf("email" to gmail.packageName))
        try {
            val shortcut = AniobIntentResolver.resolve("Summarize the newest emails in Gmail.", { true })
            assertNotNull("a read request must resolve to its app", shortcut)
            assertEquals("com.google.android.gm", shortcut!!.targetPackage)
            assertTrue(
                "opening an inbox is not reading it: the launch must be observed",
                shortcut.requiresObservation
            )
            assertEquals(
                "a launch that is observed must never finish on dispatch",
                false,
                shortcut.completesOnDispatch
            )
        } finally {
            global.clear()
        }
    }

    @Test
    fun `detection and planning agree on what is a read request`() {
        assertNotNull(AniobAppDataRead.detect("Check my two newest emails."))
        assertNull(AniobAppDataReadPlanner.planForPrompt("What is Gmail?", registry(gmail)))
    }
}
