package com.aniob.core.execution

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CompletionPublicationTest {
    @Test fun `optimized completion precedes consolidation and indexing`() = runBlocking {
        val events = mutableListOf<String>()
        CompletionPublication.complete(true, publish = { events += "message" }) {
            events += "consolidate"
            events += "index"
        }
        assertEquals(listOf("message", "consolidate", "index"), events)
    }

    @Test fun `baseline retains bookkeeping before publication`() = runBlocking {
        val events = mutableListOf<String>()
        CompletionPublication.complete(false, publish = { events += "message" }) { events += "bookkeeping" }
        assertEquals(listOf("bookkeeping", "message"), events)
    }
}
