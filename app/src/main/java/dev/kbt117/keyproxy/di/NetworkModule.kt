package dev.kbt117.keyproxy.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * The HTTP client the proxy uses to reach the upstream API.
 *
 * The timeout choices are the part worth explaining:
 *
 * - **readTimeout = 0 (infinite).** A streaming chat completion (`"stream": true`)
 *   sends server-sent events with arbitrary gaps between tokens. Any finite read
 *   timeout would abort a legitimately slow generation.
 * - **callTimeout = 0 (infinite).** Same reasoning applied to total duration; a
 *   long reasoning-model response can legitimately run for minutes.
 * - **connectTimeout = 30 s.** DNS + TCP + TLS handshake should never take that
 *   long, so this is a real bound that produces a useful error instead of a hang.
 * - **writeTimeout = 60 s.** Bounds how long we will keep pushing a request body
 *   into a stalled connection.
 *
 * Cancellation is handled by the caller closing the response body when the
 * coroutine is cancelled, not by a timeout.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        // A proxy should not silently follow cross-host redirects and replay the
        // Authorization header. OkHttp strips the header on cross-host redirects
        // by default; keeping the default (true) is fine, but disabling it means
        // the client sees the 3xx and decides, which is better proxy semantics.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
