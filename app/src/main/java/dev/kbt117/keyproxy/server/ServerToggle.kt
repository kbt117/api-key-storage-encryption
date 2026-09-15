package dev.kbt117.keyproxy.server

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers whether the *user* wants the proxy running.
 *
 * This is not a secret, so it lives in an ordinary (unencrypted) preference
 * file - keeping it out of the encrypted store means the service can read it
 * during a cold restart without initialising the Keystore.
 *
 * It exists to make `START_STICKY` safe. Without it, Android restarting the
 * service after a process kill would bring the proxy back up even when the user
 * had explicitly switched it off.
 */
@Singleton
class ServerToggle @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    var userWantsRunning: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private companion object {
        const val PREF_FILE = "keyproxy_server_toggle"
        const val KEY_ENABLED = "user_wants_running"
    }
}
