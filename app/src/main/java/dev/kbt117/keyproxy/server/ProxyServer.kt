package dev.kbt117.keyproxy.server

import dev.kbt117.keyproxy.di.IoDispatcher
import dev.kbt117.keyproxy.domain.model.ProxySettings
import dev.kbt117.keyproxy.domain.model.ServerState
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import dev.kbt117.keyproxy.logging.ProxyLogBuffer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the embedded Ktor/Netty server that listens on the loopback interface
 * and delegates every request to [UpstreamForwarder].
 *
 * ## Why loopback only
 *
 * [HOST] is hardcoded to `127.0.0.1`. That single choice carries three separate
 * guarantees:
 *
 * 1. **No LAN exposure.** An unauthenticated HTTP server that injects your API
 *    key is exactly what you do not want reachable from the coffee-shop Wi-Fi.
 *    Binding to `0.0.0.0` would make any device on the network a paying customer
 *    of your key.
 * 2. **Android 16 Local Network Protection.** LNP gates traffic to
 *    broadcast-capable networks (10/8, 172.16/12, 192.168/16, 169.254/16,
 *    100.64/10) behind a new runtime permission. Loopback is not in that list,
 *    so this app needs no `NEARBY_WIFI_DEVICES` permission. Changing [HOST]
 *    would change that answer.
 * 3. **Cleartext scope.** `network_security_config.xml` permits cleartext for
 *    `127.0.0.1` only, which matches the bind address exactly.
 *
 * ## Why Netty, and the noise it makes on Android
 *
 * The brief specifies Ktor + Netty, and Ktor's own FAQ states the Netty engine
 * works on Android API 21+. Two consequences worth knowing:
 *
 * - Netty probes for native `epoll`/`kqueue` transports, does not find them on
 *   Android, logs an exception, and falls back to NIO. **That log line is
 *   expected and harmless.**
 * - Ktor must be >= 3.3.2: earlier 3.3.x throws `java.lang.VerifyError` on
 *   Android (KTOR-8916). Pinned to 3.5.2 in the version catalog.
 *
 * Note also that the `MicrometerMetrics`/`JvmGcMetrics` plugins are deliberately
 * never installed - they reference `java.lang.management.ManagementFactory`,
 * which does not exist on Android and crashes the server.
 */
@Singleton
class ProxyServer @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val forwarder: UpstreamForwarder,
    private val logBuffer: ProxyLogBuffer,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var engine: ApplicationEngine? = null

    /** Serialises start/stop so a fast toggle cannot double-bind the port. */
    private val lifecycleMutex = Mutex()

    /**
     * Binds the listener on the port from settings.
     *
     * @return The resulting [ServerState]; never throws. A failed bind becomes
     *   [ServerState.Failed] with a message the UI can show verbatim.
     */
    suspend fun start(): ServerState = lifecycleMutex.withLock {
        // Already up - report the current port instead of failing on a
        // duplicate bind, which is what a double-tap on the switch would do.
        (_state.value as? ServerState.Running)?.let { return@withLock it }

        _state.value = ServerState.Starting

        val port = settingsRepository.current().port
        val result = withContext(ioDispatcher) { runCatching { bind(port) } }

        result.fold(
            onSuccess = {
                val running = ServerState.Running(port)
                _state.value = running
                running
            },
            onFailure = { error ->
                engine = null
                val message = ServerErrors.describeBindFailure(error, port)
                _state.value = ServerState.Failed(message)
                _state.value
            },
        )
    }

    /** Stops the server, draining in-flight requests briefly. */
    suspend fun stop() = lifecycleMutex.withLock {
        if (_state.value is ServerState.Stopped) return@withLock
        _state.value = ServerState.Stopping
        val running = engine
        engine = null
        withContext(ioDispatcher) {
            runCatching { running?.stop(GRACE_PERIOD_MS, SHUTDOWN_TIMEOUT_MS) }
        }
        _state.value = ServerState.Stopped
    }

    /**
     * Restarts if the port changed, otherwise a no-op. Called after Settings is
     * saved so the user does not have to remember to cycle the server.
     *
     * Note this deliberately does NOT hold [lifecycleMutex]: the mutex is not
     * reentrant, and both [stop] and [start] take it.
     */
    suspend fun restartIfPortChanged(requestedPort: Int) {
        val current = state.value as? ServerState.Running ?: return
        if (current.port == requestedPort) return
        stop()
        start()
    }

    // --------------------------------------------------------------- engine

    private fun bind(port: Int): ApplicationEngine {
        val server = embeddedServer(Netty, port = port, host = HOST) {
            installProxyRouting(forwarder, settingsRepository)
        }
        server.start(wait = false)
        engine = server
        return server
    }

    /** Convenience for the Dashboard's "endpoint" label. */
    fun endpointUrl(port: Int, path: String = DEFAULT_PATH): String =
        "http://$HOST:$port$path"

    companion object {
        const val HOST: String = "127.0.0.1"
        const val DEFAULT_PATH: String = "/v1/chat/completions"

        private const val GRACE_PERIOD_MS = 300L
        private const val SHUTDOWN_TIMEOUT_MS = 1_000L
    }
}

/**
 * Installs the routing table.
 *
 * Split out from the class so the `Application` receiver is explicit and the
 * dependencies are passed in rather than captured from a field - which keeps
 * the Ktor module a plain function that is easy to reason about.
 */
internal fun Application.installProxyRouting(
    forwarder: UpstreamForwarder,
    settingsRepository: SettingsRepository,
) {
    routing {
        // Local control endpoint. Never forwarded, so a client can distinguish
        // "the proxy is up but has no key" from "nothing is listening".
        get("/") {
            call.respondProxyInfo(settingsRepository.current())
        }

        // Everything else is proxied. Verbs are enumerated rather than using a
        // bare `handle {}` so the behaviour is explicit and the DSL used is the
        // plainest, most stable part of Ktor's routing API.
        route("/{path...}") {
            get { forwarder.forward(call) }
            post { forwarder.forward(call) }
            put { forwarder.forward(call) }
            patch { forwarder.forward(call) }
            delete { forwarder.forward(call) }
            head { forwarder.forward(call) }
            options { forwarder.forward(call) }
        }
    }
}

/**
 * `GET /` response: a small JSON document describing the proxy.
 *
 * Hand-built rather than serialised, consistent with the no-JSON-dependency
 * choice elsewhere. The API key is never included - only whether one is set.
 */
private suspend fun ApplicationCall.respondProxyInfo(settings: ProxySettings) {
    val json = buildString {
        append('{')
        append("\"service\":\"KeyProxy\",")
        append("\"version\":1,")
        append("\"listening\":\"").append(ProxyServer.HOST).append("\",")
        append("\"upstream\":\"")
            .append(settings.baseUrl.replace("\"", "\\\""))
            .append("\",")
        append("\"apiKeyConfigured\":").append(settings.hasApiKey).append(',')
        append("\"usage\":\"POST ").append(ProxyServer.DEFAULT_PATH).append('"')
        append('}')
    }
    respondText(json, ContentType.Application.Json, HttpStatusCode.OK)
}
