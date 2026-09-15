package dev.kbt117.keyproxy.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kbt117.keyproxy.BuildConfig
import dev.kbt117.keyproxy.data.local.EncryptedPrefsSecureKeyValueStore
import dev.kbt117.keyproxy.data.local.SecureKeyValueStore
import dev.kbt117.keyproxy.data.local.TinkSecureKeyValueStore
import dev.kbt117.keyproxy.data.repository.SettingsRepositoryImpl
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import dev.kbt117.keyproxy.security.SecurityHelper
import dev.kbt117.keyproxy.security.TinkSecurityHelper
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-wide bindings.
 *
 * Everything here is a `@Singleton`: the Keystore key, the encrypted store and
 * the settings mirror must be one instance per process, otherwise two instances
 * could race on the same preference file.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * SupervisorJob so a single failed child (e.g. one aborted proxied request)
     * cannot cancel the scope that owns the server.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideSecurityHelper(helper: TinkSecurityHelper): SecurityHelper = helper

    /**
     * Chooses the at-rest backend at build time.
     *
     * Both implementations are injected, which is safe because neither touches
     * the Keystore until first use - `EncryptedPrefsSecureKeyValueStore` builds
     * its preferences lazily and `TinkSecurityHelper` builds its `Aead` lazily.
     * So the unused backend costs nothing but an object header.
     */
    @Provides
    @Singleton
    fun provideSecureKeyValueStore(
        tink: TinkSecureKeyValueStore,
        legacy: EncryptedPrefsSecureKeyValueStore,
    ): SecureKeyValueStore = if (BuildConfig.USE_LEGACY_ENCRYPTED_PREFS) legacy else tink

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl
}
