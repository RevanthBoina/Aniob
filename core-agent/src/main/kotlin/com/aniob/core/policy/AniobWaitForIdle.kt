package com.aniob.core.policy

object AniobWaitForIdle {
    const val DEFAULT_IDLE_TIMEOUT_MS = 500L // Wait for no CONTENT_CHANGED for N ms
    const val ANIMATION_DISABLE = false // Could disable animations via Settings.Global
    
    @Volatile private var lastContentChangedTime = 0L
    @Volatile private var isIdle = true
    
    fun onContentChanged() {
        lastContentChangedTime = System.currentTimeMillis()
        isIdle = false
    }
    
    fun isUiIdle(): Boolean {
        val elapsed = System.currentTimeMillis() - lastContentChangedTime
        if (elapsed >= DEFAULT_IDLE_TIMEOUT_MS) {
            isIdle = true
        }
        return isIdle
    }
    
    suspend fun waitForIdle(timeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS * 2) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isUiIdle()) return
            kotlinx.coroutines.delay(50)
        }
    }
    
    fun reset() {
        lastContentChangedTime = System.currentTimeMillis()
        isIdle = false
    }
}
