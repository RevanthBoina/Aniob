package com.aniob.core.ladder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PART 3 — the agent reads with the account that is already signed in and only interrupts the user
 * when the choice is genuinely ambiguous, and then only once.
 */
class AniobAppReadAccountPolicyTest {

    @Test
    fun `one signed-in account is read without asking anything`() {
        val decision = AniobAppReadAccountPolicy.decide(
            listOf(AccountCandidate("me@example.com", topmost = true)),
            askedAlready = false
        )

        val proceed = decision as AccountHandover.Proceed
        assertEquals("me@example.com", proceed.label)
        assertTrue(proceed.reason.contains("without asking"))
    }

    @Test
    fun `two accounts ask exactly once with the accounts as options`() {
        val decision = AniobAppReadAccountPolicy.decide(
            listOf(
                AccountCandidate("work@example.com", topmost = true),
                AccountCandidate("home@example.com")
            ),
            askedAlready = false
        )

        val ask = decision as AccountHandover.AskOnce
        assertEquals(AniobAppReadAccountPolicy.QUESTION_ID, ask.question.id)
        assertEquals(listOf("work@example.com", "home@example.com"), ask.question.options)
        assertEquals(listOf("work@example.com", "home@example.com"), ask.candidates)
        assertEquals("work@example.com", ask.question.defaultOption)
        assertTrue(ask.question.question.contains("2 accounts"))
    }

    @Test
    fun `the same account question is never asked twice in one task`() {
        val decision = AniobAppReadAccountPolicy.decide(
            listOf(
                AccountCandidate("work@example.com", topmost = true),
                AccountCandidate("home@example.com")
            ),
            askedAlready = true
        )

        val proceed = decision as AccountHandover.Proceed
        assertEquals("work@example.com", proceed.label)
        assertTrue(proceed.askedAlready)
        assertTrue(proceed.reason.contains("instead of asking again"))
    }

    @Test
    fun `no signed-in account is a sign-in handover, not completion`() {
        val decision = AniobAppReadAccountPolicy.decide(emptyList(), askedAlready = false)

        val signIn = decision as AccountHandover.NeedsSignIn
        assertTrue(signIn.reason.contains("Sign in once"))
        assertTrue("the task is kept, so it says so", signIn.reason.contains("continue this same task"))
    }

    @Test
    fun `chooser chrome is not an account`() {
        val candidates = AniobAppReadAccountPolicy.candidatesFrom(
            listOf("Add another account", "Manage accounts", "me@example.com")
        )

        assertEquals(listOf("me@example.com"), candidates.map { it.label })
        assertTrue(candidates.single().topmost)
    }

    @Test
    fun `duplicate rows collapse so one account is not made to look ambiguous`() {
        val candidates = AniobAppReadAccountPolicy.candidatesFrom(
            listOf("me@example.com", "Me@example.com ")
        )

        assertEquals(1, candidates.size)
        val decision = AniobAppReadAccountPolicy.decide(candidates, askedAlready = false)
        assertTrue("a duplicate must not trigger a question", decision is AccountHandover.Proceed)
    }

    @Test
    fun `an answer selects the named account and a vague answer selects the topmost`() {
        val candidates = AniobAppReadAccountPolicy.candidatesFrom(
            listOf("work@example.com", "home@example.com")
        )

        assertEquals("home@example.com", AniobAppReadAccountPolicy.resolveAnswer(candidates, "home@example.com"))
        assertEquals(
            "home@example.com",
            AniobAppReadAccountPolicy.resolveAnswer(candidates, "the one for home, home@example.com please")
        )
        assertEquals(
            "work@example.com",
            AniobAppReadAccountPolicy.resolveAnswer(candidates, "not sure")
        )
        assertNull(AniobAppReadAccountPolicy.resolveAnswer(emptyList(), "anything"))
    }

    @Test
    fun `an app read plan alone never asks the user to open the app`() {
        // The planner's Ready destination is opened by Aniob itself; the only question this policy
        // can produce is about which account, and only with two or more signed-in accounts.
        val decision = AniobAppReadAccountPolicy.decide(
            listOf(AccountCandidate("me@example.com", topmost = true)),
            askedAlready = false
        )
        assertTrue(decision is AccountHandover.Proceed)
        assertNotNull(AniobAppReadAccountPolicy.QUESTION_ID)
    }
}
