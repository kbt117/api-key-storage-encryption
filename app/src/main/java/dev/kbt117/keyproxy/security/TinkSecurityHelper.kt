package dev.kbt117.keyproxy.security

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import dagger.hilt.android.qualifiers.ApplicationContext
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SecurityHelper]: Google Tink AES-256-GCM with the wrapping key held
 * in the Android Keystore.
 *
 * Why Tink rather than `androidx.security:security-crypto`?
 *
 * `EncryptedSharedPreferences` was deprecated at 1.1.0 and Google has stated no
 * further releases are planned; the current guidance is DataStore for storage
 * plus Tink for the cryptography. This class is exactly that guidance with the
 * DataStore layer swapped for `SharedPreferences`, which keeps the dependency
 * count down for what is, after all, a handful of short strings. The
 * `EncryptedSharedPreferences` backend is still available behind
 * `BuildConfig.USE_LEGACY_ENCRYPTED_PREFS` - see
 * [dev.kbt117.keyproxy.data.local.EncryptedPrefsSecureKeyValueStore].
 *
 * Layout on disk:
 *
 * ```
 * shared_prefs/keyproxy_tink_keyset.xml   Tink keyset, itself wrapped by the
 *                                         Keystore master key below
 * AndroidKeystore "keyproxy_master_key"   AES-GCM key, non-exportable
 * shared_prefs/keyproxy_secrets.xml       Base64 ciphertext written by
 *                                         TinkSecureKeyValueStore
 * ```
 *
 * The plaintext never touches any of these.
 *
 * Thread safety: [aead] is initialised once under a lock and Tink `Aead`
 * primitives are safe for concurrent use, so encrypt/decrypt can be called from
 * the proxy's worker threads without further synchronisation.
 */
@Singleton
class TinkSecurityHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecurityHelper {

    override val backendName: String = "Tink AES-256-GCM / Android Keystore"

    /**
     * Resolved lazily: querying Keymaster for hardware attestation can block,
     * and we do not want that on the DI graph's construction path.
     */
    override val isHardwareBacked: Boolean by lazy { probeHardwareBacked() }

    private val lock = Any()

    /** Cached primitive; `null` means "not built yet". */
    @Volatile
    private var cachedAead: Aead? = null

    private fun aead(): Aead {
        cachedAead?.let { return it }
        synchronized(lock) {
            cachedAead?.let { return it }
            val built = buildAead()
            cachedAead = built
            return built
        }
    }

    private fun buildAead(): Aead = try {
        // Registers the AEAD key managers with Tink's global Registry. Cheap
        // and idempotent; required before the keyset can be resolved.
        AeadConfig.register()

        AndroidKeysetManager.Builder()
            // Keyset name + prefs file. Tink creates a fresh keyset here on
            // first use because we supply a KeyTemplate.
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE))
            // Must start with AndroidKeystoreKmsClient.PREFIX. The manager
            // creates its own KMS client and generates the Keystore key if it
            // does not already exist.
            .withMasterKeyUri("$KEYSTORE_PREFIX$MASTER_KEY_ALIAS")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    } catch (e: GeneralSecurityException) {
        throw SecurityUnavailableException(
            "Could not initialise the Android Keystore key for secure storage.",
            e,
        )
    } catch (e: IOException) {
        throw SecurityUnavailableException(
            "Could not read the encrypted keyset from disk.",
            e,
        )
    }

    override fun encrypt(plaintext: String): String {
        val ciphertext = aead().encrypt(
            plaintext.toByteArray(Charsets.UTF_8),
            ASSOCIATED_DATA,
        )
        // NO_WRAP: the value goes into an XML prefs file, so no line breaks.
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? {
        if (ciphertext.isBlank()) return null
        return try {
            val raw = Base64.decode(ciphertext, Base64.NO_WRAP)
            val plaintext = aead().decrypt(raw, ASSOCIATED_DATA)
            String(plaintext, Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Auth tag mismatch: tampered, truncated, or encrypted under a
            // keyset that has since been destroyed. All mean "no value".
            null
        } catch (_: IllegalArgumentException) {
            // Not valid Base64.
            null
        } catch (_: SecurityUnavailableException) {
            // Keystore is unusable; the caller surfaces a reset action.
            null
        }
    }

    override fun destroyKeyMaterial() = synchronized(lock) {
        // 1. Drop the Tink keyset.
        context.getSharedPreferences(KEYSET_PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        // 2. Drop the Keystore master key, otherwise a freshly generated keyset
        //    would be wrapped by a key the user asked us to forget.
        runCatching {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }

        cachedAead = null
        // Note: this deliberately does NOT touch the ciphertext file. Wiping
        // that is the key-value store's job (see
        // TinkSecureKeyValueStore.destroyAll), so this class never reaches into
        // a storage layout it does not own.
    }

    /**
     * Best-effort hardware-attestation probe.
     *
     * Any failure is treated as "not hardware-backed" rather than fatal: the
     * value is informational, shown on the Settings screen so a user on an
     * emulator is not misled into thinking their key is in secure hardware.
     */
    private fun probeHardwareBacked(): Boolean = try {
        val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            // Force creation so there is something to attest.
            runCatching { aead() }
        }
        val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null) as? java.security.KeyStore.SecretKeyEntry
            ?: return false
        val factory = javax.crypto.SecretKeyFactory.getInstance(entry.secretKey.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(entry.secretKey, android.security.keystore.KeyInfo::class.java)
            as android.security.keystore.KeyInfo
        info.isInsideSecureHardware
    } catch (_: Exception) {
        false
    }

    private companion object {
        const val KEYSET_PREF_FILE = "keyproxy_tink_keyset"
        const val KEYSET_NAME = "keyproxy_aead_keyset"

        const val KEYSTORE_PREFIX = "android-keystore://"
        const val MASTER_KEY_ALIAS = "keyproxy_master_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** AES-256-GCM: authenticated encryption, no separate MAC needed. */
        const val KEY_TEMPLATE = "AES256_GCM"

        /**
         * Binds every ciphertext to this application. A blob copied from
         * another app - or a blob from an older schema version of this one -
         * fails authentication instead of decrypting to something surprising.
         */
        val ASSOCIATED_DATA: ByteArray =
            "dev.kbt117.keyproxy/secrets/v1".toByteArray(Charsets.UTF_8)
    }
}
