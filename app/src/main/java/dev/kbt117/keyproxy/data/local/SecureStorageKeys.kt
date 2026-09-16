package dev.kbt117.keyproxy.data.local

/**
 * Preference-file name and entry keys for the encrypted settings.
 *
 * Centralised so the two interchangeable backends ([TinkSecureKeyValueStore]
 * and [EncryptedPrefsSecureKeyValueStore]) address the same logical entries.
 */
object SecureStorageKeys {

    /** File holding the encrypted values. */
    const val PREF_FILE: String = "keyproxy_secrets"

    const val KEY_API_KEY: String = "api_key"
    const val KEY_BASE_URL: String = "base_url"
    const val KEY_PORT: String = "port"
}
