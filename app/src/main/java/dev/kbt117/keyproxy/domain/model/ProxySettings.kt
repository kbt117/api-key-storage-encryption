package dev.kbt117.keyproxy.domain.model

/**
 * The user-configured proxy configuration.
 *
 * Everything here is persisted: [baseUrl] and [apiKey] through the encrypted
 * store, [port] as well (it is not secret but keeping the whole record in one
 * place avoids a second storage path).
 *
 * @property baseUrl Root of the upstream OpenAI-compatible API, without a
 *   trailing slash, e.g. `https://api.openai.com`. Paths arriving at the proxy
 *   are appended verbatim (see [dev.kbt117.keyproxy.server.UpstreamUrlBuilder]).
 * @property apiKey Bearer token injected into the upstream `Authorization`
 *   header. Never logged, never returned to the UI in clear text.
 * @property port TCP port the loopback listener binds to.
 */
data class ProxySettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val apiKey: String = "",
    val port: Int = DEFAULT_PORT,
) {
    /** `true` once a non-blank key has been stored. */
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com"
        const val DEFAULT_PORT: Int = 8080

        /** Inclusive bounds for the user-editable port field. */
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
    }
}
