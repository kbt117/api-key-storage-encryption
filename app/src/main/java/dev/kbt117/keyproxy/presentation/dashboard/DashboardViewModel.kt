package dev.kbt117.keyproxy.presentation.dashboard

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kbt117.keyproxy.domain.model.ProxySettings
import dev.kbt117.keyproxy.domain.model.ServerState
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import dev.kbt117.keyproxy.logging.ProxyLogBuffer
import dev.kbt117.keyproxy.server.ProxyForegroundService
import dev.kbt117.keyproxy.server.ProxyServer
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Dashboard renders. */
data class DashboardUiState(
    val serverState: ServerState = ServerState.Stopped,
    val settings: ProxySettings = ProxySettings(),
    val stats: ProxyLogBuffer.Stats = ProxyLogBuffer.Stats(),
    val notificationsVisible: Boolean = true,
) {
    /** Full URL a client should POST to. */
    val endpoint: String
        get() {
            val port = (serverState as? ServerState.Running)?.port ?: settings.port
            return "http://${ProxyServer.HOST}:$port${ProxyServer.DEFAULT_PATH}"
        }

    /** Only the origin part, for the "listening on" line. */
    val origin: String
        get() {
            val port = (serverState as? ServerState.Running)?.port ?: settings.port
            return "http://${ProxyServer.HOST}:$port"
        }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val proxyServer: ProxyServer,
    private val logBuffer: ProxyLogBuffer,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        proxyServer.state,
        settingsRepository.settings,
        logBuffer.stats,
    ) { serverState, settings, stats ->
        DashboardUiState(
            serverState = serverState,
            settings = settings,
            stats = stats,
            notificationsVisible = ProxyForegroundService.areNotificationsVisible(appContext),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        // The repository loads from disk lazily; nudge it so the very first
        // frame can show the real base URL and port instead of the defaults.
        viewModelScope.launch { settingsRepository.current() }
    }

    /**
     * Toggles the proxy by starting/stopping the foreground service rather than
     * driving [ProxyServer] directly.
     *
     * Going through the service is what keeps the notification, the
     * `specialUse` foreground-service contract and the "user wants it running"
     * flag consistent. Driving the server straight from the ViewModel would give
     * a working proxy with no notification, which the platform treats as
     * background work it is free to kill.
     */
    fun setRunning(running: Boolean) {
        if (running) {
            ContextCompat.startForegroundService(
                appContext,
                ProxyForegroundService.startIntent(appContext),
            )
        } else {
            // Plain startService, not startForegroundService: the ACTION_STOP
            // branch of onStartCommand intentionally never calls
            // startForeground(), and pairing the two would ANR the service.
            // The app is in the foreground here, so the background-start
            // restriction does not apply.
            runCatching {
                appContext.startService(ProxyForegroundService.stopIntent(appContext))
            }.onFailure {
                runCatching {
                    appContext.stopService(ProxyForegroundService.stopIntent(appContext))
                }
            }
        }
    }

    fun clearLogs() = logBuffer.clear()
}
