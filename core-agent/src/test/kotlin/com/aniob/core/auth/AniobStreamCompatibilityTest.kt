package com.aniob.core.auth

import org.junit.Assert.assertEquals
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
}
