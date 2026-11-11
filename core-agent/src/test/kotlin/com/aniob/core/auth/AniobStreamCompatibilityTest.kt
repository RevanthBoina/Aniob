package com.aniob.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobStreamCompatibilityTest {
    @Test fun `completed item text is retained without duplicating deltas`() {
        val parser = AniobSseParser()
        val events = parser.feed("data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}}\n\n")
        val fallback = AniobStreamAccumulator()
        events.forEach { fallback.accept(it) }
        assertEquals("hello", fallback.text())
        val streamed = AniobStreamAccumulator()
        streamed.accept(AniobStreamEvent.TextDelta("hello"))
        events.forEach { streamed.accept(it) }
        assertEquals("hello", streamed.text())
    }

    @Test fun `completed and incomplete events preserve output text without failing non-filtered responses`() {
        val completedParser = AniobSseParser()
        val completedAcc = AniobStreamAccumulator()
        completedParser.feed(
            "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}}\n\n"
        ).forEach { completedAcc.accept(it) }
        assertTrue(completedAcc.completed)
        assertEquals("ok", completedAcc.text())

        val incompleteParser = AniobSseParser()
        val incompleteAcc = AniobStreamAccumulator()
        incompleteParser.feed(
            "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\"}]}]}}\n\n"
        ).forEach { incompleteAcc.accept(it) }
        assertTrue(incompleteAcc.completed)
        assertEquals("partial", incompleteAcc.text())
    }
}
