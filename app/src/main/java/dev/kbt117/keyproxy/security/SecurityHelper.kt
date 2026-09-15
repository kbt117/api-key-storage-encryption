package dev.kbt117.keyproxy.security

/**
 * Encrypts and decrypts short secret strings (API keys) for at-rest storage.
 *
 * The contract every implementation must satisfy:
 *
 * 1. **No plaintext on disk.** [encrypt] output is what gets written; the
 *    plaintext only ever exists in memory.
 * 2. **Hardware-bound.** The wrapping key lives in the Android Keystore and is
 *    not exportable, so ciphertext copied off the device cannot be decrypted
 *    elsewhere.
 * 3. **Deterministic failure, not silent corruption.** [decrypt] returns `null`
 *    for anything it cannot authenticate. It never returns garbage and never
 *    throws for tampered input.
 * 4. **Associated data bound to this app**, so ciphertext from another app (or
 *    another field of this app) will not authenticate.
 */
interface SecurityHelper {

    /** Human-readable backend name, surfaced on the Settings screen. */
    val backendName: String

    /**
     * `true` when the wrapping key is held by the hardware-backed Keystore.
     * Can be `false` on emulators or devices whose Keymaster HAL is
     * software-only, which is worth telling the user rather than hiding.
     */
    val isHardwareBacked: Boolean

    /**
     * @return An opaque, ASCII-safe token (Base64) suitable for persisting.
     * @throws SecurityUnavailableException if the Keystore or keyset could not
     *   be initialised.
     */
    fun encrypt(plaintext: String): String

    /**
     * @return The original plaintext, or `null` if [ciphertext] is malformed,
     *   was tampered with, or was produced under a keyset that no longer exists.
     */
    fun decrypt(ciphertext: String): String?

    /**
     * Destroys all local key material.
     *
     * This is the recovery path for the well-documented Android failure mode
     * where the Keystore master key is invalidated (OS update, lock-screen
     * credential change, Keymaster bug) and every subsequent decrypt fails with
     * a `GeneralSecurityException`. Without it the user is permanently locked
     * out of their own settings and must clear app data by hand.
     *
     * Callers MUST gate this behind an explicit user action: it is irreversible
     * and discards the stored API key.
     */
    fun destroyKeyMaterial()
}

/**
 * Thrown when secure storage cannot be initialised at all.
 *
 * Distinct from a decryption failure (which returns `null`) so the UI can tell
 * "your key is wrong/old" apart from "this device's Keystore is broken".
 */
class SecurityUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
