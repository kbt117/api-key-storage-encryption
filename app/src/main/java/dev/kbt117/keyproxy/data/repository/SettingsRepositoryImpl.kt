package dev.kbt117.keyproxy.data.repository

import dev.kbt117.keyproxy.data.local.SecureKeyValueStore
import dev.kbt117.keyproxy.data.local.SecureStorageKeys
import dev.kbt117.keyproxy.domain.model.ProxySettings
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.kbt117.keyproxy.di.IoDispatcher

/**
 * Persists [ProxySettings] through the [SecureKeyValueStore].
 *
 * The store is synchronous, so this class keeps an in-memory [MutableStateFlow]
 * mirror that is seeded once from disk and then updated on every write. That
 * gives the UI a hot `Flow` without a `SharedPreferences` change-listener, and
 * is safe because this process is the only writer - there is no multi-process
 * access anywhere in the app.
 *
 * All disk I/O is pushed onto the IO dispatcher: both backends perform real
 * cryptography, and on a low-end device the first `EncryptedSharedPreferences`
 * open in particular is slow enough to drop frames if it ran on the main thread.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: SecureKeyValueStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    private val _settings = MutableStateFlow(ProxySettings())

    override val settings: Flow<ProxySettings> = _settings.asStateFlow()

    /**
     * Reads from disk once. Idempotent: later calls are no-ops, so it is safe
     * to invoke from both the service and the ViewModel.
     */
    @Volatile
    private var loaded = false

    /**
     * Serialises the first load. A `synchronized` block cannot be used here:
     * the read has to suspend for the IO dispatcher, and you cannot suspend
     * inside a monitor. A `Mutex` can.
     */
    private val loadMutex = Mutex()

    override suspend fun current(): ProxySettings {
        ensureLoaded()
        return _settings.value
    }

    override suspend fun save(settings: ProxySettings) {
        ensureLoaded()
        withContext(ioDispatcher) {
            store.putString(SecureStorageKeys.KEY_BASE_URL, settings.baseUrl)
            store.putString(SecureStorageKeys.KEY_API_KEY, settings.apiKey)
            store.putInt(SecureStorageKeys.KEY_PORT, settings.port)
        }
        _settings.value = settings
    }

    override suspend fun clearApiKey() {
        ensureLoaded()
        withContext(ioDispatcher) {
            store.remove(SecureStorageKeys.KEY_API_KEY)
        }
        _settings.value = _settings.value.copy(apiKey = "")
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            // Re-check under the lock: whoever got here first has already
            // populated the mirror, and a second read could briefly resurrect
            // stale defaults over values saved in the meantime.
            if (loaded) return
            withContext(ioDispatcher) {
                _settings.value = ProxySettings(
                    baseUrl = store.getString(SecureStorageKeys.KEY_BASE_URL)
                        ?.takeIf { it.isNotBlank() }
                        ?: ProxySettings.DEFAULT_BASE_URL,
                    apiKey = store.getString(SecureStorageKeys.KEY_API_KEY).orEmpty(),
                    port = store.getInt(SecureStorageKeys.KEY_PORT, ProxySettings.DEFAULT_PORT),
                )
            }
            loaded = true
        }
    }
}
