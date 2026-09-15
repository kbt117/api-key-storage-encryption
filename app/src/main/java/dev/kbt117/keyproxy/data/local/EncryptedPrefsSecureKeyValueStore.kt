package dev.kbt117.keyproxy.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kbt117.keyproxy.security.SecurityUnavailableException
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in [SecureKeyValueStore] backed by `androidx.security`'s
 * `EncryptedSharedPreferences`.
 *
 * Selected by building with `-PuseLegacyEncryptedPrefs=true`, which sets
 * `BuildConfig.USE_LEGACY_ENCRYPTED_PREFS`. This is the backend the original
 * brief asked for; it is retained so the two approaches can be compared, not
 * because it is the recommended one.
 *
 * ⚠️ **Deprecated upstream.** Google deprecated the whole `security-crypto`
 * library at 1.1.0 and has said no further releases are planned. The published
 * 1.1.0 is stable, so this builds and runs, but it receives no fixes. The known
 * operational risk is the "keyset corruption" family of failures: on some OEM
 * images (and after certain OS updates or lock-screen credential changes) the
 * Tink keyset that `EncryptedSharedPreferences` keeps internally stops
 * authenticating and every read throws. [destroyAll] is the recovery path, and
 * it is reachable from the Settings screen for exactly that reason.
 *
 * Switching backends after keys are stored requires clearing app data: the two
 * stores use different wrapping keys and cannot read each other's ciphertext.
 */
@Singleton
class EncryptedPrefsSecureKeyValueStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SecureKeyValueStore {

    /** Built lazily: creating the master key touches the Keystore and can block. */
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    /** Retained so [isHardwareBacked] and [destroyAll] can address the same key. */
    private var masterKey: MasterKey? = null

    override val backendName: String = "EncryptedSharedPreferences (deprecated)"

    override val isHardwareBacked: Boolean
        get() = runCatching { masterKey?.isKeyStoreBacked ?: false }.getOrDefault(false)

    @Suppress("DEPRECATION") // The whole library is deprecated; see KDoc.
    private fun createEncryptedPrefs(): SharedPreferences {
        val key = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: GeneralSecurityException) {
            throw SecurityUnavailableException("Could not create the Android Keystore master key.", e)
        } catch (e: IOException) {
            throw SecurityUnavailableException("Could not create the Android Keystore master key.", e)
        }

        masterKey = key

        return try {
            EncryptedSharedPreferences.create(
                context,
                SecureStorageKeys.PREF_FILE,
                key,
                // Encrypts the *names* too, so an attacker cannot even learn
                // that an "api_key" entry exists.
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: GeneralSecurityException) {
            throw SecurityUnavailableException(
                "Could not open the encrypted preferences. The stored keyset may be corrupt; " +
                    "use \"Reset secure storage\" to recover.",
                e,
            )
        } catch (e: IOException) {
            throw SecurityUnavailableException("Could not open the encrypted preferences.", e)
        }
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    override fun destroyAll() {
        // Order matters: delete the ciphertext first so a failure part-way
        // through cannot leave readable values behind with no key.
        runCatching { context.deleteSharedPreferences(SecureStorageKeys.PREF_FILE) }
        runCatching {
            val keyStore = java.security.KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        masterKey = null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
