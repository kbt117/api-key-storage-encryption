package dev.kbt117.keyproxy.logging

import dev.kbt117.keyproxy.domain.model.ProxyLogEntry
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded, newest-first in-memory log of proxied requests, for the Logs screen.
 *
 * Deliberately **not** persisted: these entries describe traffic to a third-party
 * API, and writing them to disk would put request metadata somewhere the user
 * did not ask for it to be. Losing them on process death is the correct trade.
 *
 * Privacy rules enforced here and at every call site:
 * - request/response bodies are never recorded;
 * - header *values* are never recorded (that would include `Authorization`);
 * - only the upstream host is kept, never a full URL with a query string.
 *
 * Thread safety: the proxy serves requests from Netty worker threads, so every
 * mutation goes through [lock] and publishes an immutable snapshot.
 */
@Singleton
class ProxyLogBuffer @Inject constructor() {

    private val lock = Any()
    private val deque = ArrayDeque<ProxyLogEntry>()
    private val idSequence = AtomicLong(0L)

    private val _entries = MutableStateFlow<List<ProxyLogEntry>>(emptyList())
    val entries: StateFlow<List<ProxyLogEntry>> = _entries.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** Aggregate counters for the Dashboard. */
    data class Stats(
        val totalRequests: Int = 0,
        val failedRequests: Int = 0,
        val lastStatusCode: Int? = null,
        val lastDurationMs: Long = 0L,
    )

    /**
     * Records one exchange.
     *
     * @param statusCode Upstream status, or `null` if the call never completed.
     * @param message Failure detail. Must not contain bodies or header values.
     */
    fun record(
        method: String,
        path: String,
        upstreamHost: String,
        statusCode: Int?,
        durationMs: Long,
        message: String? = null,
    ) {
        val isError = statusCode == null || statusCode >= 400
        val entry = ProxyLogEntry(
            id = idSequence.incrementAndGet(),
            epochMillis = System.currentTimeMillis(),
            method = method,
            path = path,
            upstreamHost = upstreamHost,
            statusCode = statusCode,
            durationMs = durationMs,
            message = message,
            isError = isError,
        )

        synchronized(lock) {
            deque.addFirst(entry)
            while (deque.size > MAX_ENTRIES) deque.removeLast()
            val previous = _stats.value
            _entries.value = deque.toList()
            _stats.value = previous.copy(
                totalRequests = previous.totalRequests + 1,
                failedRequests = previous.failedRequests + if (isError) 1 else 0,
                lastStatusCode = statusCode,
                lastDurationMs = durationMs,
            )
        }
    }

    fun clear() = synchronized(lock) {
        deque.clear()
        _entries.value = emptyList()
        // Counters are intentionally kept: "clear the list" is not "reset the
        // session totals" on the Dashboard.
    }

    /** Resets both the list and the aggregate counters. */
    fun reset() = synchronized(lock) {
        deque.clear()
        _entries.value = emptyList()
        _stats.value = Stats()
    }

    private companion object {
        /** Cap keeps memory bounded on a device with a few hundred MB of heap. */
        const val MAX_ENTRIES = 200
    }
}
