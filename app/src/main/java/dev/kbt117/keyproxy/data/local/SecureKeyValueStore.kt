package dev.kbt117.keyproxy.data.local

/**
 * Key-value storage whose values are never plaintext on disk.
 *
 * Two implementations exist and are selected at build time by
 * `BuildConfig.USE_LEGACY_ENCRYPTED_PREFS`:
 *
 * - [TinkSecureKeyValueStore] (default) - Tink AEAD over `SharedPreferences`.
 * - [EncryptedPrefsSecureKeyValueStore] - `androidx.security` `EncryptedSharedPreferences`.
 *
 * The methods are synchronous on purpose: the values are tiny and the callers
 * already run on a background dispatcher. Wrapping them in `suspend` would only
 * add ceremony without changing the threading.
 */
interface SecureKeyValueStore {

    /** Backend description, surfaced in the UI so the user can see what protects their key. */
    val backendName: String

    /** Whether the wrapping key is in hardware. Informational. */
    val isHardwareBacked: Boolean

    fun putString(key: String, value: String)

    /** `null` when absent or undecryptable. */
    fun getString(key: String): String?

    fun putInt(key: String, value: Int)

    fun getInt(key: String, default: Int): Int

    fun remove(key: String)

    /** Erases the stored values but keeps the wrapping key intact. */
    fun clear()

    /**
     * Erases values *and* destroys the wrapping key material.
     *
     * This is the recovery hatch for a corrupted or invalidated Keystore key.
     * Irreversible; must only be reachable from an explicit user action.
     */
    fun destroyAll()
}
