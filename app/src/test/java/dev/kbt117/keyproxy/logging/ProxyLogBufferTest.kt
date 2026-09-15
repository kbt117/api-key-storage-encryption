package dev.kbt117.keyproxy.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProxyLogBuffer].
 *
 * Runs on the JVM with no Android dependencies - the buffer only uses
 * `System.currentTimeMillis()` and the Kotlin collections.
 */
class ProxyLogBufferTest {

    @Test
    fun `records an entry and exposes it newest first`() {
        val buffer = ProxyLogBuffer()

        buffer.record("POST", "/v1/chat/completions", "api.openai.com", 200, 42)
        buffer.record("GET", "/v1/models", "api.openai.com", 200, 11)

        val entries = buffer.entries.value
        assertEquals(2, entries.size)
        assertEquals("/v1/models", entries[0].path)
        assertEquals("/v1/chat/completions", entries[1].path)
    }

    @Test
    fun `treats a 200 as success and a 500 as an error`() {
        val buffer = ProxyLogBuffer()

        buffer.record("POST", "/v1/chat/completions", "api.openai.com", 200, 10)
        buffer.record("POST", "/v1/chat/completions", "api.openai.com", 500, 10)

        assertEquals(2, buffer.stats.value.totalRequests)
        assertEquals(1, buffer.stats.value.failedRequests)
    }

    @Test
    fun `treats a missing status code as an error`() {
        val buffer = ProxyLogBuffer()

        buffer.record(
            method = "POST",
            path = "/v1/chat/completions",
            upstreamHost = "api.openai.com",
            statusCode = null,
            durationMs = 30_000,
            message = "Could not resolve the upstream host.",
        )

        val entry = buffer.entries.value.single()
        assertNull(entry.statusCode)
        assertTrue(entry.isError)
        assertEquals(1, buffer.stats.value.failedRequests)
    }

    @Test
    fun `caps the retained entries`() {
        val buffer = ProxyLogBuffer()

        repeat(500) { index ->
            buffer.record("GET", "/v1/models/$index", "api.openai.com", 200, 1)
        }

        assertEquals(200, buffer.entries.value.size)
        // The newest entry survives; the oldest are dropped.
        assertEquals("/v1/models/499", buffer.entries.value.first().path)
        // Totals are cumulative, not capped with the list.
        assertEquals(500, buffer.stats.value.totalRequests)
    }

    @Test
    fun `clearing empties the list but keeps session totals`() {
        val buffer = ProxyLogBuffer()
        buffer.record("GET", "/v1/models", "api.openai.com", 200, 5)

        buffer.clear()

        assertTrue(buffer.entries.value.isEmpty())
        assertEquals(1, buffer.stats.value.totalRequests)
    }

    @Test
    fun `resetting empties the list and the totals`() {
        val buffer = ProxyLogBuffer()
        buffer.record("GET", "/v1/models", "api.openai.com", 401, 5)

        buffer.reset()

        assertTrue(buffer.entries.value.isEmpty())
        assertEquals(0, buffer.stats.value.totalRequests)
        assertEquals(0, buffer.stats.value.failedRequests)
        assertNull(buffer.stats.value.lastStatusCode)
    }

    @Test
    fun `ids are unique and monotonically increasing`() {
        val buffer = ProxyLogBuffer()
        repeat(10) { buffer.record("GET", "/v1/models", "api.openai.com", 200, 1) }

        val ids = buffer.entries.value.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(ids.sortedDescending(), ids)
    }
}
