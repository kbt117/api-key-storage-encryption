package dev.kbt117.keyproxy.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kbt117.keyproxy.domain.SettingsValidator
import dev.kbt117.keyproxy.presentation.theme.ErrorRed
import dev.kbt117.keyproxy.presentation.theme.SuccessGreen

/**
 * Screen 2 from the brief: Base API URL and API key, plus the port and a
 * connection test.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UpstreamCard(state, viewModel)
        CredentialsCard(state, viewModel)
        StorageCard(state, viewModel)

        state.message?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) ErrorRed else SuccessGreen,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.isSaving) "Saving…" else "Save") }

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !state.isTesting,
                modifier = Modifier.weight(1f),
            ) { Text(if (state.isTesting) "Testing…" else "Test") }
        }
    }
}

@Composable
private fun UpstreamCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    var presetsExpanded by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Upstream API", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("Base API URL") },
                placeholder = { Text("https://api.openai.com") },
                singleLine = true,
                isError = state.baseUrlError != null,
                supportingText = state.baseUrlError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { presetsExpanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose a preset")
                }
                DropdownMenu(
                    expanded = presetsExpanded,
                    onDismissRequest = { presetsExpanded = false },
                ) {
                    SettingsValidator.PRESETS.forEach { (label, url) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                viewModel.onApplyPreset(url)
                                presetsExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = state.portText,
                onValueChange = viewModel::onPortChange,
                label = { Text("Local port") },
                singleLine = true,
                isError = state.portError != null,
                supportingText = state.portError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CredentialsCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Credentials", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::onApiKeyChange,
                label = { Text("API key") },
                singleLine = true,
                // Masked unless the user explicitly asks to see it. The value is
                // still held in the ViewModel in clear text while editing - that
                // is unavoidable for a text field - but it is never persisted
                // unencrypted and never logged.
                visualTransformation = if (state.showKey) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                supportingText = { Text("Stored as: ${state.maskedKey}") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    TextButton(onClick = viewModel::onToggleShowKey) {
                        Text(if (state.showKey) "Hide" else "Show")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StorageCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    var confirmReset by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.width(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("At-rest encryption", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = state.backendName.ifBlank { "Initialising…" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (state.isHardwareBacked) {
                    "Wrapping key is in secure hardware."
                } else {
                    "Wrapping key is Keystore-managed but not hardware-attested " +
                        "(common on emulators)."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (confirmReset) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "This permanently deletes the stored key and the wrapping key. " +
                        "Continue?",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.resetSecureStorage()
                        confirmReset = false
                    }) { Text("Reset") }
                    OutlinedButton(onClick = { confirmReset = false }) { Text("Cancel") }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { confirmReset = true }) {
                    Text("Reset secure storage")
                }
            }
        }
    }
}
