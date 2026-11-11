package com.aniob.core

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobRect
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SuccessCriteria
import com.aniob.core.verify.FastVerify
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two-tier verify policy: change first, expected text second, bitmap only on timeout. */
class FastVerifyTest {

    private fun state(
        hash: String,
        packageName: String = "com.example.app",
        texts: List<String> = emptyList()
    ) = AniobScreenState(
        packageName = packageName,
        treeHash = hash,
        screenHash = hash,
        nodes = texts.mapIndexed { index, text ->
            AniobNode(id = index + 1, className = "android.widget.TextView", text = text, bounds = AniobRect(0, index * 50, 300, index * 50 + 40))
        }
    )

    @Test fun finalCheapCaptureCanAvoidBitmap() = runBlocking {
        var captures = 0
        var bitmaps = 0
        val result = FastVerify.await(preAction = state("before"), timeoutMs = 0,
            capturePoll = { captures++; state(if (captures == 1) "before" else "after") },
            screenshot = { bitmaps++; "bitmap" }, finalCaptureAtTimeout = true)
        assertEquals(2, captures)
        assertEquals(0, bitmaps)
        assertFalse(result.screenshotTaken)
        assertEquals(FastVerify.Tier.CHANGED, result.tier)
    }

    @Test
    fun identityPrefersTheStructuralHashAndFallsBackToTheTreeHash() {
        assertEquals("structural", FastVerify.screenIdentity(state("structural")))
        val legacy = AniobScreenState(packageName = "p", treeHash = "tree", screenHash = "")
        assertEquals("tree", FastVerify.screenIdentity(legacy))
    }

    @Test
    fun changedStructurePassesWithoutPixels() {
        val decision = FastVerify.evaluate("hash-a", state("hash-b"))
        assertEquals(FastVerify.Tier.CHANGED, decision.tier)
        assertTrue(decision.passed)
    }

    @Test
    fun expectedTextPassesWhenTheStructureHashIsUnchanged() {
        val decision = FastVerify.evaluate("hash-a", state("hash-a", texts = listOf("Pizza near you")), listOf("pizza"))
        assertEquals(FastVerify.Tier.EXPECTED_TEXT, decision.tier)
        assertEquals("pizza", decision.matchedText)
    }

    @Test
    fun textIsMatchedAgainstDescriptionToo() {
        val screen = AniobScreenState(
            packageName = "p",
            treeHash = "h",
            screenHash = "h",
            nodes = listOf(AniobNode(id = 1, className = "View", contentDescription = "Search results"))
        )
        assertEquals(FastVerify.Tier.EXPECTED_TEXT, FastVerify.evaluate("h", screen, listOf("results")).tier)
    }

    @Test
    fun unchangedScreenWithinTheBudgetTimesOut() {
        val decision = FastVerify.evaluate("hash-a", state("hash-a"))
        assertEquals(FastVerify.Tier.TIMEOUT, decision.tier)
        assertFalse(decision.passed)
    }

    @Test
    fun packageSwitchCountsAsATransition() {
        val decision = FastVerify.evaluate(
            preActionIdentity = "hash-a",
            screen = state("hash-a", packageName = "com.other.app"),
            preActionPackage = "com.example.app"
        )
        assertEquals(FastVerify.Tier.CHANGED, decision.tier)
    }

    @Test
    fun missingCaptureNeverPasses() {
        assertEquals(FastVerify.Tier.TIMEOUT, FastVerify.evaluate("hash-a", null).tier)
    }

    @Test
    fun expectedTextsCombineTypedValueAndTaskCriteria() {
        val texts = FastVerify.expectedTexts(
            AniobAction.InputText(SemanticTarget.SomIndex(1), "pizza"),
            SuccessCriteria(mustContainText = listOf("order confirmed"))
        )
        assertEquals(listOf("pizza", "order confirmed"), texts)
        assertEquals(emptyList<String>(), FastVerify.expectedTexts(AniobAction.Tap(SemanticTarget.SomIndex(1)), null))
    }

    @Test
    fun aWindowEventEndsTheWaitInsteadOfPollingTheTreeOnEveryTick() = runBlocking {
        var events = 0L
        var captures = 0
        // The service reports the transition shortly after the action.
        val transition = launch {
            delay(5)
            events = 1
        }
        val outcome = FastVerify.await(
            preAction = state("hash-a"),
            pollMs = 1,
            timeoutMs = 500,
            windowEvents = { events },
            capturePoll = {
                captures++
                if (events > 0) state("hash-b") else state("hash-a")
            },
            screenshot = { error("tier 2 must not run after a change") }
        )
        transition.join()
        assertEquals(FastVerify.Tier.CHANGED, outcome.tier)
        // A baseline read, at most one periodic safety read, and the event read.
        assertTrue("event-driven wait must stay near one read", captures <= 3)
        assertTrue("must not wait out the 800 ms budget", outcome.elapsedMs < 200)
        assertFalse("no bitmap on the tier-1 happy path", outcome.screenshotTaken)
    }

    @Test
    fun withoutEventsOnlyTheSafetyCadenceReadsTheTree() = runBlocking {
        var ticks = 0
        var captures = 0
        var screenshots = 0
        val outcome = FastVerify.await(
            preAction = state("hash-a"),
            pollMs = 1,
            timeoutMs = 200,
            windowEvents = { ticks++; 7L }, // steady counter: no transition ever arrives
            capturePoll = { captures++; state("hash-a") },
            screenshot = { screenshots++; "jpg" }
        )
        assertEquals(FastVerify.Tier.TIMEOUT, outcome.tier)
        assertTrue("enough ticks to matter", ticks > 10)
        assertTrue(
            "every fourth tick reads the tree",
            captures <= ticks / FastVerify.CAPTURE_EVERY_TICKS + 2
        )
        assertTrue("the event gate must save reads", captures < ticks)
        assertEquals(1, screenshots)
        assertTrue(outcome.screenshotTaken)
        assertEquals("jpg", outcome.screenshotBase64)
    }

    @Test
    fun textAlreadyVisibleBeforeTheActionIsNotEvidence() = runBlocking {
        val before = state("hash-a", texts = listOf("Order placed"))
        var screenshots = 0
        val outcome = FastVerify.await(
            preAction = before,
            expectedTexts = listOf("Order placed"),
            pollMs = 1,
            timeoutMs = 20,
            windowEvents = { 0L },
            capturePoll = { before },
            screenshot = { screenshots++; null }
        )
        assertEquals(FastVerify.Tier.TIMEOUT, outcome.tier)
        assertEquals("tier 2 runs once on the miss", 1, screenshots)
    }

    @Test
    fun budgetMatchesTheDocumentedNumbers() {
        assertEquals(50L, FastVerify.POLL_INTERVAL_MS)
        assertEquals(800L, FastVerify.MAX_WAIT_MS)
    }
}
