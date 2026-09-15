package dev.kbt117.keyproxy.data.local

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kbt117.keyproxy.security.SecurityHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SecureKeyValueStore]: ciphertext produced by [SecurityHelper]
 * (Tink AES-256-GCM, master key in the Android Keystore), stored as strings in
 * an ordinary `SharedPreferences` file.
 *
 * Because the prefs file contains only Base64 ciphertext, a `adb backup` leak,
 * a rooted read of `shared_prefs/`, or a stray logcat dump of the file yields
 * nothing usable. The plaintext exists only inside [putString]/[getString]
 * stack frames.
 *
 * Note on the int accessor: the port is not secret, but storing it through the
 * same authenticated channel keeps the whole settings record under one
 * integrity check rather than mixing a plaintext file with an encrypted one.
 */
@Singleton
class TinkSecureKeyValueStore @Inject constructor(
    @ApplicationContext context: Context,
    private val securityHelper: SecurityHelper,
) : SecureKeyValueStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(SecureStorageKeys.PREF_FILE, Context.MODE_PRIVATE)

    override val backendName: String get() = securityHelper.backendName

    override val isHardwareBacked: Boolean get() = securityHelper.isHardwareBacked

    override fun putString(key: String, value: String) {
        // Encrypt first, then write. If encryption throws we leave the previous
        // value untouched rather than storing a half-updated record.
        val encrypted = securityHelper.encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return securityHelper.decrypt(stored)
    }

    override fun putInt(key: String, value: Int) = putString(key, value.toString())

    override fun getInt(key: String, default: Int): Int =
        getString(key)?.toIntOrNull() ?: default

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    override fun destroyAll() {
        clear()
        securityHelper.destroyKeyMaterial()
    }
}
