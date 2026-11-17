package com.aniob.core.execution

import com.aniob.core.domain.AniobNode
import com.aniob.core.domain.AniobScreenState
import com.aniob.core.intent.AniobAppDataRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The honesty rules for "read the newest emails".
 *
 * Field failure: a run opened Gmail, tapped twice, proposed `Finish` and was reported as a success
 * while nothing had been read. No success criterion could disprove it, because a read request names
 * no text to look for — the only honest evidence is *content that was on the screen*.
 */
class AppReadEvidenceTest {

    private val gmail = "com.google.android.gm"

    private fun screen(pkg: String, vararg texts: String) = AniobScreenState(
        packageName = pkg,
        nodes = texts.mapIndexed { index, text ->
            AniobNode(id = index + 1, className = "android.widget.TextView", text = text, isClickable = true)
        }
    )

    private fun evidence(prompt: String) = AppReadEvidence(
        request = AniobAppDataRead.detect(prompt),
        targetPackages = setOf(gmail)
    )

    @Test
    fun `two observed message rows satisfy a request for two messages`() {
        val evidence = evidence("Check my two newest emails.")
        evidence.observe(
            screen(
                gmail,
                "Asha Rao — Quarterly plan",
                "Vikram — Invoice for March",
                "Inbox"
            ),
            1
        )

        assertTrue(evidence.isSatisfied())
        assertEquals(2, evidence.distinctCount())
        assertNull(evidence.unmetReason())
    }

    @Test
    fun `navigation chrome is not a message`() {
        val evidence = evidence("Check my two newest emails.")
        evidence.observe(screen(gmail, "Inbox", "Compose", "Search"), 1)

        assertEquals("only row-like content counts", 0, evidence.distinctCount())
        assertFalse(evidence.isSatisfied())
    }

    @Test
    fun `opening the app without reading anything cannot complete the request`() {
        val evidence = evidence("Check my two newest emails.")
        // Gmail is in front, but the capture holds only its toolbar.
        evidence.observe(screen(gmail, "Inbox", "Search"), 1)

        val verdict = AppReadCompletionGate.evaluate(evidence)
        assertTrue("a read request may not complete on an unchanged inbox", verdict.blocksCompletion)
        assertNotNull((verdict as AppReadCompletionGate.Verdict.Rejected).reason)
    }

    @Test
    fun `a partial read is reported honestly, with what was seen and what is missing`() {
        val evidence = evidence("Check my three newest emails.")
        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan"), 1)

        val verdict = AppReadCompletionGate.evaluate(evidence) as AppReadCompletionGate.Verdict.Rejected
        assertTrue("the reason must say how much was actually read: ${verdict.reason}", verdict.reason.contains("only observed 1 of the 3"))
        assertTrue("the reason must not claim the content was read: ${verdict.reason}", verdict.reason.contains("cannot"))
    }

    @Test
    fun `screens from another app never count as evidence`() {
        val evidence = evidence("Check my two newest emails.")
        evidence.observe(screen("com.android.chrome", "Asha Rao — Quarterly plan", "Vikram — Invoice"), 1)

        assertEquals(0, evidence.distinctCount())
        assertFalse(evidence.reachedTargetApp())
        assertTrue(
            AppReadEvidence(
                request = AniobAppDataRead.detect("Check my two newest emails."),
                targetPackages = setOf(gmail)
            ).unmetReason().orEmpty().isNotBlank()
        )
    }

    @Test
    fun `scrolling adds new evidence but repeating a row does not inflate the count`() {
        val evidence = evidence("Check my newest emails.")
        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan"), 1)
        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan"), 2)
        assertEquals(1, evidence.distinctCount())

        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan", "Vikram — Invoice"), 3)
        assertEquals(2, evidence.distinctCount())
        assertTrue(evidence.items().any { it.stepIndex == 3 })
    }

    @Test
    fun `the evidence list quotes only observed text`() {
        val evidence = evidence("Check my newest emails.")
        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan", "Vikram — Invoice for March"), 2)

        assertEquals(
            listOf("Asha Rao — Quarterly plan", "Vikram — Invoice for March"),
            evidence.evidenceLines()
        )
    }

    @Test
    fun `a non read task is unaffected by the gate`() {
        val verdict = AppReadCompletionGate.evaluate(null)
        assertFalse(verdict.blocksCompletion)
        assertTrue(verdict is AppReadCompletionGate.Verdict.NotAReadRequest)
    }

    @Test
    fun `an unspecified count needs at least one observed item`() {
        val evidence = evidence("Summarize the newest emails in Gmail.")
        assertFalse("one observed message is the minimum", evidence.isSatisfied())
        evidence.observe(screen(gmail, "Asha Rao — Quarterly plan"), 1)
        assertTrue(evidence.isSatisfied())
    }
}
