package dev.kbt117.keyproxy.presentation.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kbt117.keyproxy.domain.model.ServerState
import dev.kbt117.keyproxy.domain.model.isRunning
import dev.kbt117.keyproxy.logging.ProxyLogBuffer
import dev.kbt117.keyproxy.presentation.theme.ErrorRed
import dev.kbt117.keyproxy.presentation.theme.SuccessGreen
import dev.kbt117.keyproxy.presentation.theme.WarningAmber
import kotlinx.coroutines.launch

/**
 * Screen 1 from the brief: server on/off, the local endpoint URL, and enough
 * status to tell at a glance whether the proxy is healthy.
 */
@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServerCard(
                serverState = state.serverState,
                onToggle = viewModel::setRunning,
            )

            // A missing key is the single most common reason a freshly toggled
            // proxy returns 401, so it is surfaced here rather than buried.
            if (!state.settings.hasApiKey) {
                WarningCard(
                    title = "No API key stored",
                    body = "The proxy is running, but every request will be rejected with 401 " +
                        "until you add a key.",
                    actionLabel = "Open Settings",
                    onAction = onOpenSettings,
                )
            }

            // POST_NOTIFICATIONS is deliberately not requested (see the
            // manifest), so on API 33+ the notification can be hidden. The
            // proxy still works; the user just loses the indicator and the
            // Stop shortcut.
            if (state.serverState.isRunning && !state.notificationsVisible) {
                WarningCard(
                    title = "Notification hidden",
                    body = "The proxy is running, but Android is hiding its notification, so " +
                        "there is no quick Stop control. Enable notifications for KeyProxy in " +
                        "system settings to restore it.",
                    actionLabel = null,
                    onAction = null,
                )
            }

            EndpointCard(
                endpoint = state.endpoint,
                onCopy = {
                    context.copyToClipboard("Local endpoint", state.endpoint)
                    scope.launch { snackbarHostState.showSnackbar("Endpoint copied") }
                },
            )

            UpstreamCard(baseUrl = state.settings.baseUrl, port = state.settings.port)

            StatsCard(stats = state.stats, onClear = viewModel::clearLogs)
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ServerCard(serverState: ServerState, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Server status",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = serverState.isRunning || serverState is ServerState.Starting,
                    onCheckedChange = onToggle,
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(serverState)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = statusDescription(serverState),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(state: ServerState) {
    val (label, color) = when (state) {
        is ServerState.Running -> "RUNNING" to SuccessGreen
        is ServerState.Starting -> "STARTING" to WarningAmber
        is ServerState.Stopping -> "STOPPING" to WarningAmber
        is ServerState.Failed -> "FAILED" to ErrorRed
        is ServerState.Stopped -> "STOPPED" to MaterialTheme.colorScheme.outline
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.18f)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun statusDescription(state: ServerState): String = when (state) {
    is ServerState.Running -> "Listening on port ${state.port}"
    is ServerState.Starting -> "Binding the socket…"
    is ServerState.Stopping -> "Draining connections…"
    is ServerState.Failed -> state.message
    is ServerState.Stopped -> "Not listening"
}

@Composable
private fun EndpointCard(endpoint: String, onCopy: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Local endpoint", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = endpoint,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Point any OpenAI-compatible client at this URL. It is reachable only " +
                    "from this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCopy) { Text("Copy URL") }
        }
    }
}

@Composable
private fun UpstreamCard(baseUrl: String, port: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LabeledLine("Upstream", baseUrl)
            LabeledLine("Port", port.toString())
        }
    }
}

@Composable
private fun StatsCard(stats: ProxyLogBuffer.Stats, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "This session",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Reset") }
            }
            Spacer(Modifier.height(4.dp))
            LabeledLine("Requests", stats.totalRequests.toString())
            LabeledLine("Failed", stats.failedRequests.toString())
            LabeledLine(
                "Last status",
                stats.lastStatusCode?.toString() ?: "—",
                valueColor = stats.lastStatusCode?.let { if (it >= 400) ErrorRed else SuccessGreen },
            )
            LabeledLine("Last duration", "${stats.lastDurationMs} ms")
        }
    }
}

@Composable
private fun LabeledLine(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun WarningCard(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = WarningAmber.copy(alpha = 0.12f),
        ),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall)
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onAction) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

/** Small extension to keep the copy call sites readable. */
private fun Context.copyToClipboard(label: String, value: String) {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, value))
}
