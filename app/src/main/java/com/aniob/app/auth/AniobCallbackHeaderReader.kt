package com.aniob.app.auth

import java.io.InputStream

/** Bounded HTTP header lines. Update the socket timeout only on a bulk refill, not per byte.
 * Both the total byte budget and the absolute deadline apply across all lines in a request.
 */
internal class AniobCallbackHeaderReader(
    private val input: InputStream,
    private val deadline: Long,
    maxBytes: Int,
    private val now: () -> Long,
    private val beforeRead: (timeoutMs: Int) -> Unit
) {
    private val buffer = ByteArray(1024)
    private var position = 0
    private var available = 0
    private var remaining = maxBytes

    fun readLine(): String? {
        val result = StringBuilder()
        while (remaining > 0) {
            val wait = deadline - now()
            if (wait <= 0L) return null
            if (position == available) {
                beforeRead(wait.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1))
                available = input.read(buffer, 0, minOf(buffer.size, remaining))
                position = 0
                if (available <= 0) return null
            }
            val c = buffer[position++].toInt() and 0xff
            remaining--
            if (c == 10) return result.toString().removeSuffix("\r")
            result.append(c.toChar())
        }
        return null
    }
}
