package com.aniob.core.bench

import com.aniob.core.bench.AniobBenchmarkManifest.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AniobBenchmarkManifestTest {

    @Test
    fun manifestContainsExactlyTenHarmlessTasks() {
        val tasks = AniobBenchmarkManifest.TASKS
        assertEquals(10, tasks.size)

        // Verify task IDs are unique
        val uniqueIds = tasks.map { it.taskId }.toSet()
        assertEquals(10, uniqueIds.size)
    }

    @Test
    fun manifestSeparatesShortcutsFromModelTasks() {
        val shortcuts = AniobBenchmarkManifest.getShortcutTasks()
        val modelTasks = AniobBenchmarkManifest.getModelGenerationTasks()

        assertEquals(3, shortcuts.size)
        assertEquals(7, modelTasks.size)

        shortcuts.forEach {
            assertEquals(Track.DETERMINISTIC_SHORTCUT, it.track)
            assertTrue(it.observableCriteria.hasObservableCheck)
        }

        modelTasks.forEach {
            assertEquals(Track.MODEL_GENERATION, it.track)
            assertTrue("Model tasks must have observable completion criteria", it.observableCriteria.hasObservableCheck)
            assertTrue("Model tasks must have safe timeout", it.timeoutSeconds in 15..60)
            assertTrue("Model tasks must have bounded steps", it.maxSteps in 3..10)
        }
    }

    @Test
    fun modelTasksDoNotCountShortcutOnlySuccesses() {
        val modelTasks = AniobBenchmarkManifest.getModelGenerationTasks()
        modelTasks.forEach { task ->
            // Model generation tasks require multi-step navigation, tapping or searching
            assertTrue(task.allowedActionTypes.contains("tap") || task.allowedActionTypes.contains("input_text"))
        }
    }
}
