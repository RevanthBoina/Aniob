package com.aniob.core.workflow

import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.RegisteredApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkflowRouterTest {

    @Before
    fun resetRegistry() {
        AniobAppRegistry.resetForTest()
    }

    @After
    fun clearRegistry() {
        AniobAppRegistry.resetForTest()
    }

    @Test
    fun testPreservesUppercaseUrlPathAndQuery() {
        val prompt = "Open https://example.com/MyPath/SubPath?QueryParam=UPPER"
        val decision = WorkflowRouter.route(prompt)

        assertTrue("Expected DirectAction for valid HTTPS link", decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action
        assertTrue(action is WorkflowAction.OpenWebsite)
        val openWebsite = action as WorkflowAction.OpenWebsite
        assertEquals("https://example.com/MyPath/SubPath?QueryParam=UPPER", openWebsite.destination.url)
    }

    @Test
    fun testDisambiguatesExplanationFromCreation() {
        // Informational question -> delegate to existing Q&A
        val explanationDecision = WorkflowRouter.route("explain how to create a website")
        assertTrue(explanationDecision is WorkflowDecision.DelegateToExisting)

        val howToDecision = WorkflowRouter.route("how do i create a website")
        assertTrue(howToDecision is WorkflowDecision.DelegateToExisting)

        // Actual creation command -> ask which service rather than inventing a chooser list.
        val creationDecision = WorkflowRouter.route("build a website for my bakery")
        assertTrue(creationDecision is WorkflowDecision.NeedsClarification)
        assertTrue((creationDecision as WorkflowDecision.NeedsClarification).question.contains("Which app or website"))
    }

    @Test
    fun websiteCreationAsksWhichServiceWithoutInventingChoices() {
        val decision = WorkflowRouter.route("create a website")
        assertTrue(decision is WorkflowDecision.NeedsClarification)
        assertTrue((decision as WorkflowDecision.NeedsClarification).question.contains("I won't choose a service"))
    }

    @Test
    fun hostedReasoningUsesTheExistingRouteUnlessTheUserChoosesADestination() {
        val prompt = "ask chatgpt to solve this logic puzzle"
        val defaultDecision = WorkflowRouter.route(prompt)
        assertTrue(defaultDecision is WorkflowDecision.DelegateToExisting)

        val chosen = Destination(
            handler = "chatgpt.com",
            url = "https://chatgpt.com/",
            category = DestinationCategory.WEBSITE,
            provenance = DestinationProvenance.USER_PREFERENCE
        )
        val explicitDecision = WorkflowRouter.route(prompt, userPreferredDestination = chosen)
        assertTrue(explicitDecision is WorkflowDecision.DirectAction)
        val action = (explicitDecision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenWebsite
        assertEquals(chosen.url, action.destination.url)
    }

    @Test
    fun noteCreationUsesOnlyAnInstalledNotesApp() {
        AniobAppRegistry.getInstance().hydrate(
            listOf(RegisteredApp(packageName = "com.example.notes", label = "Notes"))
        )
        val decision = WorkflowRouter.route("create a note to buy groceries")
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenApp
        assertEquals("com.example.notes", action.destination.handler)
        assertEquals("Notes", action.destination.label)
        assertEquals("buy groceries", action.draftText)

        AniobAppRegistry.resetForTest()
        val missing = WorkflowRouter.route("create a note to buy groceries")
        assertTrue("Missing notes app must be clarified, not guessed", missing is WorkflowDecision.NeedsClarification)
    }

    @Test
    fun testSummarizeUrlCarriesBriefText() {
        val prompt = "Summarize https://example.com/report"
        val decision = WorkflowRouter.route(prompt)
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenWebsite
        assertEquals("https://example.com/report", action.destination.url)
        assertEquals(prompt, action.briefText)
    }

    @Test
    fun testCompareChatGPTDelegatesToExisting() {
        val decision = WorkflowRouter.route("Compare ChatGPT and Gemini")
        assertTrue(decision is WorkflowDecision.DelegateToExisting)
    }

    @Test
    fun unknownNamedAppAsksForARealInstalledDestination() {
        val decision = WorkflowRouter.route("Open Spotify")
        assertTrue(decision is WorkflowDecision.NeedsClarification)
        assertTrue((decision as WorkflowDecision.NeedsClarification).question.contains("installed app"))
    }

    @Test
    fun installedNamedAppIsOpenedWithItsRealLabel() {
        AniobAppRegistry.getInstance().hydrate(
            listOf(RegisteredApp(packageName = "com.example.player", label = "Music Player"))
        )
        val decision = WorkflowRouter.route("Open Music Player")
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenApp
        assertEquals("com.example.player", action.destination.handler)
        assertEquals("Music Player", action.destination.label)
    }

    @Test
    fun unavailableCandidatesAreRemovedBeforeRoutingAnAmbiguousNotesTask() {
        AniobAppRegistry.getInstance().hydrate(
            listOf(
                RegisteredApp(packageName = "com.example.notes.a", label = "Notes"),
                RegisteredApp(packageName = "com.example.notes.b", label = "Notes")
            )
        )
        val decision = WorkflowRouter.route("Open Notes") { packageName ->
            packageName == "com.example.notes.b"
        }
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenApp
        assertEquals("com.example.notes.b", action.destination.handler)
        assertEquals("Notes", action.destination.label)
    }

    @Test
    fun ambiguousInstalledNotesAppsAreNamedInTheChooser() {
        AniobAppRegistry.getInstance().hydrate(
            listOf(
                RegisteredApp(packageName = "com.example.colornote", label = "ColorNote", aliases = listOf("notes")),
                RegisteredApp(packageName = "com.example.notepad", label = "Notepad", aliases = listOf("notes"))
            )
        )
        val decision = WorkflowRouter.route("create a note to buy groceries")
        assertTrue(decision is WorkflowDecision.NeedsChooser)
        val chooser = decision as WorkflowDecision.NeedsChooser
        assertEquals(listOf("ColorNote", "Notepad"), chooser.candidates.map { it.label })
        assertTrue(chooser.candidates.all { it.category == DestinationCategory.APP })
    }

    @Test
    fun testFtpUrlRejected() {
        val sanitized = WorkflowRouter.validateAndSanitizeUrl("ftp://example.com/file.txt")
        org.junit.Assert.assertNull(sanitized)
    }

    @Test
    fun testPureUrlDoesNotCreateBrief() {
        val decision = WorkflowRouter.route("Open https://example.com/report")
        assertTrue(decision is WorkflowDecision.DirectAction)
        val action = (decision as WorkflowDecision.DirectAction).action as WorkflowAction.OpenWebsite
        assertEquals("https://example.com/report", action.destination.url)
        org.junit.Assert.assertNull(action.briefText)
    }

    @Test
    fun testInformationalChatGptQuestionDoesNotTriggerExternalAppOpen() {
        val decision = WorkflowRouter.route("Is ChatGPT free?")
        assertTrue(decision is WorkflowDecision.DelegateToExisting)
    }

    @Test
    fun testUrlWithWhitespaceOrCredentialsRejected() {
        val credUrl = WorkflowRouter.validateAndSanitizeUrl("https://user:pass@example.com/doc")
        org.junit.Assert.assertNull(credUrl)

        val spaceUrl = WorkflowRouter.validateAndSanitizeUrl("https://exa mple.com/doc")
        org.junit.Assert.assertNull(spaceUrl)
    }
}
