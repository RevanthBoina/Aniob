package com.aniob.app.provider

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one provider instance per session (a task id, a conversation id, a fixed role name).
 *
 * A provider used to be constructed for every step. One instance per session lets everything a
 * provider holds for its lifetime be reused across the steps of a task, and makes "how many
 * providers did this task create" a number that can be logged and asserted (it must be 1).
 *
 * [fingerprint] identifies the settings the provider was built from: when the key, address or
 * model changes, the next request gets a fresh instance instead of a stale one.
 */
class AniobProviderRegistry<P : Any>(
    private val maxSessions: Int = 16,
    private val build: (sessionId: String) -> P
) {
    private data class Entry<P>(val fingerprint: String, val provider: P)

    private val entries = ConcurrentHashMap<String, Entry<P>>()
    private val builds = ConcurrentHashMap<String, AtomicInteger>()

    fun get(sessionId: String, fingerprint: String): P {
        entries[sessionId]?.takeIf { it.fingerprint == fingerprint }?.let { return it.provider }
        synchronized(this) {
            entries[sessionId]?.takeIf { it.fingerprint == fingerprint }?.let { return it.provider }
            if (entries.size >= maxSessions && !entries.containsKey(sessionId)) {
                entries.clear()
                builds.clear()
            }
            val provider = build(sessionId)
            entries[sessionId] = Entry(fingerprint, provider)
            builds.getOrPut(sessionId) { AtomicInteger() }.incrementAndGet()
            return provider
        }
    }

    /** How many instances were built for [sessionId] since it was last released. */
    fun buildCount(sessionId: String): Int = builds[sessionId]?.get() ?: 0

    /** Forgets the session and returns how many instances it built. */
    fun release(sessionId: String): Int {
        entries.remove(sessionId)
        return builds.remove(sessionId)?.get() ?: 0
    }
}
