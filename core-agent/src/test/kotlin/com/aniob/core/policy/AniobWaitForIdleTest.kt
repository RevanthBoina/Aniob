package com.aniob.core.policy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AniobWaitForIdleTest {
    @Test fun `baseline and fast budgets remain distinct`() {
        assertEquals(500L, AniobWaitForIdle.BASELINE_QUIET_MS)
        assertEquals(1000L, AniobWaitForIdle.BASELINE_CAP_MS)
        assertEquals(250L, AniobWaitForIdle.FAST_QUIET_MS)
        assertEquals(400L, AniobWaitForIdle.FAST_CAP_MS)
    }

    @Test fun `visual noise does not reset optimized quiet period`() = runBlocking {
        AniobWaitForIdle.reset()
        kotlinx.coroutines.delay(260)
        AniobWaitForIdle.onContentChanged("android.widget.ProgressBar", ignoreVisualNoise = true)
        val started = System.nanoTime()
        AniobWaitForIdle.waitForIdle(fast = true)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 200)
        AniobWaitForIdle.onContentChanged("android.widget.ProgressBar", ignoreVisualNoise = false)
        assertFalse(AniobWaitForIdle.isUiIdle())
    }
}
