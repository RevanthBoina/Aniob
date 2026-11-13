package com.aniob.core.memory

import org.junit.Assert.*
import org.junit.Test

class ConversationKnowledgeStoreTest {
    @Test fun factsAreScopedAndDeletingOneChatPreservesOthers() {
        val backing = InMemorySharedKnowledgeStore()
        backing.put("legacy", "not conversation context")
        val a = ConversationKnowledgeStore(backing, "a")
        val b = ConversationKnowledgeStore(backing, "b")
        a.put("name", "Alice")
        assertNull(b.get("name"))
        assertTrue(b.getAll().isEmpty())
        b.put("name", "Ravi")
        assertEquals("Alice", ConversationKnowledgeStore(backing, "a").get("name"))
        a.clear()
        assertEquals("Ravi", b.get("name"))
        assertEquals("not conversation context", backing.get("legacy"))
    }
}
