package dev.kbt117.keyproxy.di

import javax.inject.Qualifier

/**
 * Marks the dispatcher used for blocking I/O: Keystore/crypto work, OkHttp
 * calls, and the embedded server's lifecycle.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Marks the dispatcher used for CPU-bound work (currently unused, kept for symmetry). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Marks the application-scoped [kotlinx.coroutines.CoroutineScope].
 *
 * Survives Activity recreation, which matters here: the proxy server and its
 * log buffer are process-level singletons, not screen-level state.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
