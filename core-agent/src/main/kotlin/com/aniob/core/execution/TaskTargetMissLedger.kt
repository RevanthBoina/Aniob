package com.aniob.core.execution

/**
 * Retry memory scoped to one task and one recoverable semantic target. A miss never becomes a
 * process-wide block; a new task starts with an empty ledger.
 */
class TaskTargetMissLedger {
    private var taskId: String = ""
    private val misses = mutableMapOf<String, Int>()

    @Synchronized
    fun beginTask(newTaskId: String) {
        if (taskId != newTaskId) {
            taskId = newTaskId
            misses.clear()
        }
    }

    @Synchronized
    fun recordMiss(forTaskId: String, targetKey: String): Int {
        if (forTaskId != taskId || targetKey.isBlank()) return 0
        return (misses[targetKey] ?: 0).plus(1).also { misses[targetKey] = it }
    }

    @Synchronized
    fun recordSuccess(forTaskId: String, targetKey: String) {
        if (forTaskId != taskId || targetKey.isBlank()) return
        misses.remove(targetKey)
    }

    @Synchronized
    fun clear() {
        taskId = ""
        misses.clear()
    }
}
