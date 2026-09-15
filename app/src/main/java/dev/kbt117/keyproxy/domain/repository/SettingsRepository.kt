package dev.kbt117.keyproxy.domain.repository

import dev.kbt117.keyproxy.domain.model.ProxySettings
import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to the persisted [ProxySettings].
 *
 * Implementations must guarantee that [apiKey] is never written to disk in
 * clear text, and that [settings] only ever emits values decrypted in memory.
 */
interface SettingsRepository {

    /** Cold-flow-able, hot observable of the current settings. */
    val settings: Flow<ProxySettings>

    /** One-shot read of the current settings. */
    suspend fun current(): ProxySettings

    /** Replaces the whole record atomically. */
    suspend fun save(settings: ProxySettings)

    /** Erases the stored API key without touching the base URL or port. */
    suspend fun clearApiKey()
}
