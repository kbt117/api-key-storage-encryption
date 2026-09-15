package dev.kbt117.keyproxy.domain.model

/**
 * One line in the proxy request log shown on the Logs screen.
 *
 * @property epochMillis Wall-clock time of the request. Stored as a raw `Long`
 *   rather than `java.time.Instant` so the type stays trivially copyable into
 *   Compose state and needs no formatting infrastructure in tests.
 * @property method HTTP verb as received, e.g. `POST`.
 * @property path Request path on the proxy, e.g. `/v1/chat/completions`.
 * @property upstreamHost Host the request was forwarded to. Redacted to a host
 *   only - never a full URL with credentials.
 * @property statusCode Upstream status, or `null` when the request never
 *   completed (timeout, DNS failure, connection reset).
 * @property durationMs End-to-end wall time of the proxied exchange.
 * @property message Human-readable detail for failures. Never contains the
 *   request/response body or any header value.
 * @property isError `true` when the exchange failed locally or upstream
 *   returned >= 400.
 */
data class ProxyLogEntry(
    val id: Long,
    val epochMillis: Long,
    val method: String,
    val path: String,
    val upstreamHost: String,
    val statusCode: Int?,
    val durationMs: Long,
    val message: String?,
    val isError: Boolean,
)
