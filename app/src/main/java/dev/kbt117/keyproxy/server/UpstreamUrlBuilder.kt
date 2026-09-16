package dev.kbt117.keyproxy.server

/**
 * Pure resolution of an upstream URL from the configured base URL plus the path
 * and query that arrived at the local proxy.
 *
 * Kept as a dependency-free object so it can be exhaustively unit tested on the
 * JVM - URL construction is the single most likely place for a subtle proxy bug.
 *
 * The one piece of user-friendliness baked in here is `/v1` de-duplication:
 * OpenAI's docs say the base URL is `https://api.openai.com`, while OpenRouter
 * and most gateways say it is `https://host/api/v1`. Clients almost always send
 * `/v1/...` regardless, so a naive concatenation produces
 * `https://host/api/v1/v1/chat/completions`. We collapse that.
 */
object UpstreamUrlBuilder {

    /**
     * @param baseUrl Configured upstream root. Trailing slashes are ignored.
     * @param requestPath Path received by the proxy, e.g. `/v1/chat/completions`.
     *   A missing leading slash is tolerated.
     * @param rawQuery Query string without the leading `?`, or `null`/blank.
     * @return Absolute URL to call upstream.
     * @throws IllegalArgumentException if [baseUrl] is blank or not an absolute
     *   `http`/`https` URL - callers translate this into a 500 with a clear
     *   message rather than letting a malformed request reach OkHttp.
     */
    fun resolve(baseUrl: String, requestPath: String, rawQuery: String?): String {
        val normalizedBase = normalizeBaseUrl(baseUrl)
        val normalizedPath = normalizePath(requestPath)

        val combined = deduplicateVersionSegment(normalizedBase, normalizedPath)
        return if (rawQuery.isNullOrBlank()) combined else "$combined?$rawQuery"
    }

    /**
     * Trims whitespace and any trailing `/` so callers can concatenate safely.
     * Does not otherwise rewrite the URL - we deliberately do not lowercase the
     * host or reorder query parameters of the *base*, since that would change
     * what the user asked for.
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Base API URL must not be blank." }
        require(
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true),
        ) { "Base API URL must start with https:// (or http:// for a local endpoint)." }
        return trimmed
    }

    /** Ensures exactly one leading slash and strips a trailing one. */
    fun normalizePath(requestPath: String): String {
        val withLeadingSlash = if (requestPath.startsWith("/")) requestPath else "/$requestPath"
        return withLeadingSlash.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * Drops a duplicated leading `/v1` when the base URL already ends in one.
     *
     * Only the *first* segment is considered, and only the exact literal `v1`,
     * so `/v1/v1/foo` (a genuinely unusual path) is left alone after the first
     * collapse.
     */
    internal fun deduplicateVersionSegment(base: String, path: String): String {
        val baseEndsWithV1 = base.endsWith("/v1", ignoreCase = true)
        val pathStartsWithV1 =
            path.equals("/v1", ignoreCase = true) ||
                path.startsWith("/v1/", ignoreCase = true)

        if (!baseEndsWithV1 || !pathStartsWithV1) return "$base$path"

        val remainder = path.removePrefix("/v1").removePrefix("/V1")
        // "/v1" -> remainder is "" -> the base already is the full target.
        return if (remainder.isEmpty()) base else "$base$remainder"
    }

    /**
     * Extracts just the host (and port) for logging. Never returns a path or
     * query, so a log line can never leak a credential embedded in a URL.
     */
    fun hostForLogging(url: String): String = try {
        val withoutScheme = url.substringAfter("://", url)
        withoutScheme.substringBefore('/').substringBefore('?')
    } catch (_: IndexOutOfBoundsException) {
        "unknown"
    }
}
