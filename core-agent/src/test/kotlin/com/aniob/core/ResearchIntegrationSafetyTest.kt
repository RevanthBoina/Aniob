package com.aniob.core

import com.aniob.core.domain.*
import com.aniob.core.memory.RememberAction
import com.aniob.core.memory.RememberCache
import com.aniob.core.perception.BurstTargetGuard
import org.junit.Assert.*
import org.junit.Test

class ResearchIntegrationSafetyTest {
    private val node = AniobNode(id = 1, className = "Button", viewId = "app:id/row", text = "Recipient 12", isClickable = true)
    private val screen = AniobScreenState(packageName = "app", activityName = "Main", nodes = listOf(node), screenHash = "before")

    @Test fun `numeric labels and suffixes are identities not disposable noise`() {
        assertNotEquals(BurstTargetGuard.normalizeLabel("Recipient 12"), BurstTargetGuard.normalizeLabel("Recipient 13"))
        assertNotEquals(BurstTargetGuard.normalizeLabel("Alex | work"), BurstTargetGuard.normalizeLabel("Alex | personal"))
        assertNull(BurstTargetGuard.match(listOf(node.copy(text = "Recipient 13")), node))
    }

    @Test fun `duplicate target identities and missing widgets never fall back to nearest bounds`() {
        assertNull(BurstTargetGuard.match(listOf(node, node.copy(id = 2)), node))
        assertNull(BurstTargetGuard.match(listOf(node.copy(viewId = "app:id/other", text = "Other")), node))
        val ambiguousOrigin = screen.copy(nodes = listOf(node, node.copy(id = 2)))
        assertFalse(BurstTargetGuard.check(AniobAction.Tap(SemanticTarget.ResourceId(node.viewId)), ambiguousOrigin, screen).allowed)
    }

    @Test fun `same package activity navigation closes the old burst`() {
        assertTrue(BurstTargetGuard.screenNavigated(screen, screen.copy(activityName = "Detail")))
    }

    @Test fun `replay metadata and divergence survive persistence and lookup does not invent hits`() {
        val cache = RememberCache()
        val descriptor = RememberAction.fromAction(AniobAction.Tap(SemanticTarget.SomIndex(1)), node, screen)!!
        assertTrue(cache.record("select recipient", "app", "before", listOf(descriptor), expectedNextHash = "after"))
        val entry = cache.snapshot().single()
        assertNotNull(cache.lookup("select recipient", screen))
        assertEquals(0, cache.snapshot().single().hits)
        cache.recordSuccess(entry.key)
        cache.recordFailure(entry.key)
        val restored = RememberCache.fromJson(cache.toJson())
        assertEquals(cache.snapshot(), restored.snapshot())
        assertEquals("after", restored.snapshot().single().expectedNextHash)
        assertEquals(1, restored.snapshot().single().hits)
        restored.recordFailure(entry.key)
        assertEquals(0, restored.size)
    }

    @Test fun `legacy replay rows load with safe metadata defaults`() {
        val legacy = """{"version":1,"entries":[{"goal":"recipient-select","package":"app","hash":"before","updated":1,"hits":0,"actions":[{"kind":"tap","viewId":"app:id/row","text":"Recipient 12"}]}]}"""
        val entry = RememberCache.fromJson(legacy).snapshot().single()
        assertEquals("", entry.expectedNextHash)
        assertEquals(0, entry.failures)
        assertEquals(1, entry.successCount)
    }

    @Test fun `password fields and short numeric secrets never seed replay`() {
        assertNull(RememberAction.fromAction(AniobAction.InputText(SemanticTarget.SomIndex(1), "secret"), node.copy(isPassword = true), screen))
        assertNull(RememberAction.fromAction(AniobAction.InputText(SemanticTarget.SomIndex(1), "123456"), node, screen))
    }
}
