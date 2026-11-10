package com.aniob.core.policy

object AniobWaitForIdle {
    const val QUIET_WINDOW_MS = 250L
    const val HARD_CAP_MS = 400L
    const val DEFAULT_IDLE_TIMEOUT_MS = QUIET_WINDOW_MS
    const val ANIMATION_DISABLE = false

    @Volatile private var lastContentChangedTime = 0L
    @Volatile private var isIdle = true

    private val VISUAL_NOISE_CLASSES = listOf(
        "progressbar",
        "seekbar",
        "videoview",
        "textureview",
        "chronometer",
        "webview"
    )

    fun isVisualNoise(className: CharSequence?): Boolean {
        if (className == null) return false
        val lower = className.toString().lowercase(java.util.Locale.ROOT)
        return VISUAL_NOISE_CLASSES.any { lower.contains(it) }
    }

    fun onContentChanged(sourceClassName: CharSequence? = null) {
        if (sourceClassName != null && isVisualNoise(sourceClassName)) {
            return
        }
        lastContentChangedTime = System.currentTimeMillis()
        isIdle = false
    }

    fun isUiIdle(quietWindowMs: Long = QUIET_WINDOW_MS): Boolean {
        val elapsed = System.currentTimeMillis() - lastContentChangedTime
        if (elapsed >= quietWindowMs) {
            isIdle = true
        }
        return isIdle
    }

    suspend fun waitForIdle(
        quietWindowMs: Long = QUIET_WINDOW_MS,
        hardCapMs: Long = HARD_CAP_MS
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < hardCapMs) {
            val quiet = System.currentTimeMillis() - lastContentChangedTime
            if (quiet >= quietWindowMs) return
            val remaining = (hardCapMs - (System.currentTimeMillis() - start)).coerceAtLeast(0L)
            val stepDelay = (quietWindowMs - quiet).coerceIn(10L, 50L).coerceAtMost(remaining)
            if (stepDelay <= 0L) return
            kotlinx.coroutines.delay(stepDelay)
        }
    }

    fun reset() {
        lastContentChangedTime = System.currentTimeMillis()
        isIdle = false
    }
}
