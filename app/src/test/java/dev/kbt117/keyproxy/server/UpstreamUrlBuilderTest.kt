package dev.kbt117.keyproxy.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests for [UpstreamUrlBuilder].
 *
 * This is the highest-value test class in the project: a wrong URL is the most
 * likely proxy bug and the hardest to diagnose from the outside, because the
 * failure surfaces as a confusing 404 from a third party.
 */
class UpstreamUrlBuilderTest {

    // ------------------------------------------------------- base cases

    @Test
    fun `appends path to a plain origin`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            UpstreamUrlBuilder.resolve(
                baseUrl = "https://api.openai.com",
                requestPath = "/v1/chat/completions",
                rawQuery = null,
            ),
        )
    }

    @Test
    fun `strips a trailing slash from the base url`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            UpstreamUrlBuilder.resolve("https://api.openai.com/", "/v1/models", null),
        )
    }

    @Test
    fun `strips multiple trailing slashes from the base url`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            UpstreamUrlBuilder.resolve("https://api.openai.com///", "/v1/models", null),
        )
    }

    @Test
    fun `tolerates a request path without a leading slash`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            UpstreamUrlBuilder.resolve("https://api.openai.com", "v1/models", null),
        )
    }

    // --------------------------------------------- /v1 de-duplication

    @Test
    fun `collapses duplicated v1 when base already ends in v1`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            UpstreamUrlBuilder.resolve(
                baseUrl = "https://api.openai.com/v1",
                requestPath = "/v1/chat/completions",
                rawQuery = null,
            ),
        )
    }

    @Test
    fun `collapses duplicated v1 for a gateway with a path prefix`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            UpstreamUrlBuilder.resolve(
                baseUrl = "https://openrouter.ai/api/v1",
                requestPath = "/v1/chat/completions",
                rawQuery = null,
            ),
        )
    }

    @Test
    fun `collapses when the path is exactly v1`() {
        assertEquals(
            "https://api.openai.com/v1",
            UpstreamUrlBuilder.resolve("https://api.openai.com/v1", "/v1", null),
        )
    }

    @Test
    fun `does not collapse when base has no v1 suffix`() {
        // The client asked for /v1/...; the base has no version segment, so the
        // path must be preserved verbatim.
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            UpstreamUrlBuilder.resolve("https://api.openai.com", "/v1/chat/completions", null),
        )
    }

    @Test
    fun `does not collapse a v2 path`() {
        assertEquals(
            "https://api.openai.com/v1/v2/chat/completions",
            UpstreamUrlBuilder.resolve("https://api.openai.com/v1", "/v2/chat/completions", null),
        )
    }

    @Test
    fun `does not collapse a v10 path`() {
        // "v10" starts with "v1" textually; a naive prefix check would corrupt it.
        assertEquals(
            "https://api.openai.com/v1/v10/thing",
            UpstreamUrlBuilder.resolve("https://api.openai.com/v1", "/v10/thing", null),
        )
    }

    // ------------------------------------------------------------ query

    @Test
    fun `preserves the query string`() {
        assertEquals(
            "https://api.openai.com/v1/models?limit=5&after=x",
            UpstreamUrlBuilder.resolve(
                baseUrl = "https://api.openai.com",
                requestPath = "/v1/models",
                rawQuery = "limit=5&after=x",
            ),
        )
    }

    @Test
    fun `omits the question mark when the query is blank`() {
        assertEquals(
            "https://api.openai.com/v1/models",
            UpstreamUrlBuilder.resolve("https://api.openai.com", "/v1/models", "   "),
        )
    }

    // ----------------------------------------------------------- errors

    @Test
    fun `rejects a blank base url`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpstreamUrlBuilder.resolve("", "/v1/models", null)
        }
    }

    @Test
    fun `rejects a base url with no scheme`() {
        assertThrows(IllegalArgumentException::class.java) {
            UpstreamUrlBuilder.resolve("api.openai.com", "/v1/models", null)
        }
    }

    @Test
    fun `accepts http for a local endpoint`() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            UpstreamUrlBuilder.resolve(
                "http://127.0.0.1:11434",
                "/v1/chat/completions",
                null,
            ),
        )
    }

    // ------------------------------------------------------ log safety

    @Test
    fun `host extraction never leaks a path or query`() {
        assertEquals(
            "api.openai.com",
            UpstreamUrlBuilder.hostForLogging(
                "https://api.openai.com/v1/chat/completions?api_key=SECRET",
            ),
        )
    }

    @Test
    fun `host extraction keeps the port`() {
        assertEquals(
            "127.0.0.1:11434",
            UpstreamUrlBuilder.hostForLogging("http://127.0.0.1:11434/v1/models"),
        )
    }

    @Test
    fun `host extraction survives a malformed url`() {
        assertEquals("nonsense", UpstreamUrlBuilder.hostForLogging("nonsense"))
    }
}
