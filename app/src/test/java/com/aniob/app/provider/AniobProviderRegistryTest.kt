package com.aniob.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AniobProviderRegistryTest {
    @Test fun oneInstancePerTaskAcrossManySteps() {
        var built = 0
        val registry = AniobProviderRegistry { session -> built++; Any() to session }
        val first = registry.get("task_1", "cfg")
        repeat(20) { assertSame(first, registry.get("task_1", "cfg")) }
        assertEquals(1, built)
        assertEquals(1, registry.buildCount("task_1"))
        assertEquals(1, registry.release("task_1"))
        assertEquals(0, registry.buildCount("task_1"))
    }

    @Test fun sessionsDoNotShareAnInstance() {
        val registry = AniobProviderRegistry { session -> Any() to session }
        assertNotSame(registry.get("task_1", "cfg"), registry.get("task_2", "cfg"))
        assertEquals("task_2", registry.get("task_2", "cfg").second)
    }

    @Test fun changedSettingsRebuildAndAreCounted() {
        val registry = AniobProviderRegistry { session -> Any() to session }
        val before = registry.get("task_1", "key-a")
        val after = registry.get("task_1", "key-b")
        assertNotSame(before, after)
        assertSame(after, registry.get("task_1", "key-b"))
        assertEquals(2, registry.buildCount("task_1"))
    }

    @Test fun registryIsBounded() {
        var built = 0
        val registry = AniobProviderRegistry(maxSessions = 2) { session -> built++; session }
        registry.get("a", "c"); registry.get("b", "c"); registry.get("c", "c")
        registry.get("a", "c")
        assertEquals(4, built)
    }
}
