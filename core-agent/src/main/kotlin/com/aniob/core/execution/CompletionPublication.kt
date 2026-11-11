package com.aniob.core.execution

/** Controls ordering only; verdict computation must finish before calling this seam. */
object CompletionPublication {
    suspend fun complete(immediate: Boolean, publish: () -> Unit, bookkeeping: suspend () -> Unit) {
        if (immediate) {
            publish()
            bookkeeping()
        } else {
            bookkeeping()
            publish()
        }
    }
}
