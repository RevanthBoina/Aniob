package com.aniob.app.auth

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AniobCallbackHeaderReaderTest {
    @Test fun `normal headers use one bulk read and one timeout update`() {
        val headers = "GET /auth/callback HTTP/1.1\r\nHost: localhost\r\n\r\n"
        val timeouts = mutableListOf<Int>()
        val reader = AniobCallbackHeaderReader(ByteArrayInputStream(headers.toByteArray()), 3000L, 8192,
            { 0L }, { timeouts += it })
        assertEquals("GET /auth/callback HTTP/1.1", reader.readLine())
        assertEquals("Host: localhost", reader.readLine())
        assertEquals("", reader.readLine())
        assertEquals(listOf(3000), timeouts)
    }

    @Test fun `header budget applies across lines not separately per line`() {
        val reader = AniobCallbackHeaderReader(ByteArrayInputStream("a\r\nb\r\n".toByteArray()), 3000L, 5, { 0L }, {})
        assertEquals("a", reader.readLine())
        assertNull(reader.readLine())
    }

    @Test fun `buffered data cannot bypass the absolute deadline`() {
        var now = 0L
        val reader = AniobCallbackHeaderReader(ByteArrayInputStream("a\r\nb\r\n".toByteArray()), 3000L, 8192, { now }, {})
        assertEquals("a", reader.readLine())
        now = 3000L
        assertNull(reader.readLine())
    }

    @Test fun `each refill uses only the remaining absolute deadline`() {
        var now = 0L
        val timeouts = mutableListOf<Int>()
        val input = object : ByteArrayInputStream("a".repeat(1024).plus("b\r\n").toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                now += 1000L
                return count
            }
        }
        val reader = AniobCallbackHeaderReader(input, 3000L, 8192, { now }, { timeouts += it })
        assertEquals(1025, reader.readLine()!!.length)
        assertEquals(listOf(3000, 2000), timeouts)
    }

    @Test fun `slow input does not reset the deadline on each received byte`() {
        var now = 0L
        val input = object : ByteArrayInputStream("a\r\n".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, 1)
                now += 1600L
                return count
            }
        }
        val reader = AniobCallbackHeaderReader(input, 3000L, 8192, { now }, {})
        assertNull(reader.readLine())
        assertTrue(now < 5000L)
    }
}
