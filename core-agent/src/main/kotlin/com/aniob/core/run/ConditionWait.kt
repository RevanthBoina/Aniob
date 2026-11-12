package com.aniob.core.run

import kotlinx.coroutines.delay

/**
 * Waiting on a condition with a deadline instead of waiting on a clock.
 *
 * A fixed `delay(600)` always costs 600 ms, even when the thing it was waiting for happened after
 * 60. [until] returns the moment its condition holds and never waits longer than the old sleep
 * did, so the worst case is unchanged and the common case is faster.
 */
object ConditionWait {

    const val DEFAULT_STEP_MS = 40L

    /** How long the window-event counter has to stand still before a screen counts as settled. */
    const val WINDOW_QUIET_MS = 100L

    /**
     * @return true when [condition] held before [deadlineMs] ran out, or holds on the final check.
     */
    suspend fun until(
        deadlineMs: Long,
        stepMs: Long = DEFAULT_STEP_MS,
        now: () -> Long = System::currentTimeMillis,
        condition: () -> Boolean
    ): Boolean {
        val end = now() + deadlineMs
        while (now() < end) {
            if (condition()) return true
            // Never sleep past the deadline: the last step is cut to what is left of the budget.
            delay(stepMs.coerceAtMost(end - now()).coerceAtLeast(1L))
        }
        return condition()
    }

    /**
     * "The screen has stopped moving", read from a monotonic window-event counter.
     *
     * Create it **before** the action whose effect is awaited, then poll [settled]:
     *  - with [requireMovement], the counter must first move past the value it had at creation
     *    (the scroll or relaunch really produced events) and then stand still for [quietMs];
     *  - without it, standing still for [quietMs] is enough (a screen that may already be idle).
     *
     * If nothing ever moves under [requireMovement], [settled] stays false and the caller's
     * deadline applies - exactly the old fixed wait.
     */
    class WindowQuiet(
        private val quietMs: Long = WINDOW_QUIET_MS,
        private val requireMovement: Boolean,
        private val now: () -> Long = System::currentTimeMillis,
        private val sequence: () -> Long
    ) {
        private val startSequence = sequence()
        private var lastSequence = startSequence
        private var lastChangeAt = now()

        fun settled(): Boolean {
            val current = sequence()
            val at = now()
            if (current != lastSequence) {
                lastSequence = current
                lastChangeAt = at
                return false
            }
            if (requireMovement && current == startSequence) return false
            return at - lastChangeAt >= quietMs
        }
    }
}
