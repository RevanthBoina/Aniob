package com.aniob.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Structured fan-out for work that is genuinely independent.
 *
 * Only independent work belongs here (clarification planning, engine warm-up, connection warm-up,
 * capability probes). The observe -> act -> verify loop is causally ordered, and a phone has one
 * screen and one touch path, so device actions are never parallelised.
 *
 * Every helper is structured: a cancelled caller (Stop) cancels all children, and a failing
 * child cancels its siblings instead of leaking.
 */
object AniobParallel {

    /** Workers for CPU-bound fan-out: use the cores, but never starve a small device. */
    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    /** Runs [left] and [right] concurrently and returns both results. First failure cancels the other. */
    suspend fun <A, B> both(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        left: suspend () -> A,
        right: suspend () -> B
    ): Pair<A, B> = coroutineScope {
        val l = async(dispatcher) { left() }
        val r = async(dispatcher) { right() }
        l.await() to r.await()
    }

    /** Order-preserving parallel map with at most [parallelism] items in flight. */
    suspend fun <T, R> mapBounded(
        items: Collection<T>,
        parallelism: Int = defaultParallelism(),
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        transform: suspend (T) -> R
    ): List<R> {
        if (items.isEmpty()) return emptyList()
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        return coroutineScope {
            items.map { item -> async(dispatcher) { gate.withPermit { transform(item) } } }.awaitAll()
        }
    }

    /**
     * For warm-ups and other optional work: never throws and never blocks longer than [timeoutMs].
     * Cancellation is still honoured, so Stop is never swallowed.
     */
    suspend fun <T> bestEffort(timeoutMs: Long, fallback: T, block: suspend () -> T): T =
        withContext(Dispatchers.Default) {
            try {
                withTimeoutOrNull(timeoutMs) { block() } ?: fallback
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                fallback
            }
        }
}
