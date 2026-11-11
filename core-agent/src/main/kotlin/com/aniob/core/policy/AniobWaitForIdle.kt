package com.aniob.core.policy

object AniobWaitForIdle {
    const val BASELINE_QUIET_MS = 500L
    const val BASELINE_CAP_MS = 1_000L
    const val FAST_QUIET_MS = 250L
    const val FAST_CAP_MS = 400L
    const val DEFAULT_IDLE_TIMEOUT_MS = BASELINE_QUIET_MS
    const val ANIMATION_DISABLE = false
    private val noiseClasses = listOf("progressbar", "seekbar", "videoview", "surfaceview", "textureview", "chronometer", "webview")
    @Volatile private var lastMeaningfulChangeMs = 0L
    private fun now(): Long = System.nanoTime() / 1_000_000L

    fun onContentChanged(sourceClass: CharSequence? = null, ignoreVisualNoise: Boolean = false) {
        val normalized = sourceClass?.toString()?.lowercase(java.util.Locale.ROOT).orEmpty()
        if (ignoreVisualNoise && noiseClasses.any(normalized::contains)) return
        lastMeaningfulChangeMs = now()
    }

    fun isUiIdle(): Boolean = now() - lastMeaningfulChangeMs >= BASELINE_QUIET_MS

    suspend fun waitForIdle(fast: Boolean = false) {
        val quietMs = if (fast) FAST_QUIET_MS else BASELINE_QUIET_MS
        val capMs = if (fast) FAST_CAP_MS else BASELINE_CAP_MS
        val started = now()
        while (now() - started < capMs) {
            if (now() - lastMeaningfulChangeMs >= quietMs) return
            kotlinx.coroutines.delay(25L)
        }
    }

    fun reset() { lastMeaningfulChangeMs = now() }
}
