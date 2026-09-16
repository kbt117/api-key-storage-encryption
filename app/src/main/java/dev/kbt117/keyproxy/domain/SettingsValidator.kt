package dev.kbt117.keyproxy.domain

import dev.kbt117.keyproxy.domain.model.ProxySettings

/** Result of validating one user-supplied field. */
sealed interface Validation {
    data object Valid : Validation

    /** @property message User-facing, safe to show verbatim in the UI. */
    data class Invalid(val message: String) : Validation
}

/**
 * Validation and normalisation for the Settings screen.
 *
 * Pure functions, no Android dependencies, so the ViewModel can call them on
 * every keystroke without a coroutine hop and the rules can be unit tested.
 */
object SettingsValidator {

    /** Base URLs offered as one-tap presets on the Settings screen. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "OpenAI" to "https://api.openai.com",
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "Groq" to "https://api.groq.com/openai/v1",
        "Together AI" to "https://api.together.xyz/v1",
        "DeepInfra" to "https://api.deepinfra.com/v1/openai",
        "Ollama (this device)" to "http://127.0.0.1:11434/v1",
    )

    fun validateBaseUrl(raw: String): Validation {
        val value = raw.trim()
        if (value.isEmpty()) return Validation.Invalid("Base API URL is required.")

        val lower = value.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return Validation.Invalid("Must start with https:// (or http:// for a local endpoint).")
        }

        // Reject obvious typos early rather than at first proxy call.
        //
        // The port must be stripped before inspecting the host: comparing
        // "localhost:8080" against "localhost" directly would wrongly reject a
        // perfectly good local endpoint. IPv6 literals are bracketed, so the
        // brackets are removed too.
        val authority = value.substringAfter("://").substringBefore('/')
        val host = authority.substringBefore(':').removeSurrounding("[", "]")

        if (host.isBlank()) return Validation.Invalid("That URL has no host.")

        val isLoopback = host.equals("localhost", ignoreCase = true) ||
            host.startsWith("127.") ||
            host == "::1"

        // A real hostname needs a dot; a bare word that is not loopback is
        // almost always a typo ("htps://apiopenai.com" style mistakes).
        if (!isLoopback && !host.contains('.')) {
            return Validation.Invalid("That does not look like a reachable host.")
        }
        return Validation.Valid
    }

    fun validatePort(raw: String): Validation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validation.Invalid("Port is required.")
        val parsed = trimmed.toIntOrNull()
            ?: return Validation.Invalid("Port must be a whole number.")
        return when {
            parsed < ProxySettings.MIN_PORT ->
                Validation.Invalid("Use a port of ${ProxySettings.MIN_PORT} or higher.")
            parsed > ProxySettings.MAX_PORT ->
                Validation.Invalid("Use a port of ${ProxySettings.MAX_PORT} or lower.")
            else -> Validation.Valid
        }
    }

    /**
     * Strips surrounding whitespace and a single trailing slash so the stored
     * value concatenates cleanly with request paths.
     */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    /**
     * Renders a key for display without revealing it: `sk-ab…wxyz`.
     *
     * Returns a fixed placeholder for short or empty values rather than
     * partially echoing them, since for a short string a prefix *is* the secret.
     */
    fun maskApiKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "not set"
        if (trimmed.length <= 12) return "•".repeat(8)
        return "${trimmed.take(6)}…${trimmed.takeLast(4)}"
    }
}
