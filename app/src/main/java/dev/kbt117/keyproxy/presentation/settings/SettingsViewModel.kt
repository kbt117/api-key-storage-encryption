package dev.kbt117.keyproxy.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kbt117.keyproxy.data.local.SecureKeyValueStore
import dev.kbt117.keyproxy.di.IoDispatcher
import dev.kbt117.keyproxy.domain.SettingsValidator
import dev.kbt117.keyproxy.domain.Validation
import dev.kbt117.keyproxy.domain.model.ProxySettings
import dev.kbt117.keyproxy.domain.repository.SettingsRepository
import dev.kbt117.keyproxy.server.ProxyServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class SettingsUiState(
    val baseUrl: String = ProxySettings.DEFAULT_BASE_URL,
    val apiKey: String = "",
    val maskedKey: String = "not set",
    val portText: String = ProxySettings.DEFAULT_PORT.toString(),
    val showKey: Boolean = false,
    val baseUrlError: String? = null,
    val portError: String? = null,
    val isSaving: Boolean = false,
    val isTesting: Boolean = false,
    val backendName: String = "",
    val isHardwareBacked: Boolean = false,
    /** Transient status line; `null` hides it. */
    val message: String? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val store: SecureKeyValueStore,
    private val proxyServer: ProxyServer,
    private val httpClient: OkHttpClient,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settingsRepository.current()
            _uiState.update {
                it.copy(
                    baseUrl = saved.baseUrl,
                    apiKey = saved.apiKey,
                    maskedKey = SettingsValidator.maskApiKey(saved.apiKey),
                    portText = saved.port.toString(),
                    backendName = store.backendName,
                    isHardwareBacked = store.isHardwareBacked,
                )
            }
        }
    }

    fun onBaseUrlChange(value: String) = _uiState.update {
        it.copy(baseUrl = value, baseUrlError = null, message = null)
    }

    fun onApiKeyChange(value: String) = _uiState.update {
        it.copy(apiKey = value, maskedKey = SettingsValidator.maskApiKey(value), message = null)
    }

    fun onPortChange(value: String) = _uiState.update {
        // Digits only: a port field that can hold letters just moves the error
        // to save time for no benefit.
        it.copy(portText = value.filter(Char::isDigit).take(5), portError = null, message = null)
    }

    fun onToggleShowKey() = _uiState.update { it.copy(showKey = !it.showKey) }

    fun onApplyPreset(url: String) = _uiState.update {
        it.copy(baseUrl = url, baseUrlError = null, message = null)
    }

    /**
     * Validates, persists, and restarts the server if the port moved.
     *
     * The API key travels from the form straight into the encrypted store; it is
     * never written to logs and never echoed back to the UI except through
     * [SettingsUiState.maskedKey].
     */
    fun save() {
        val current = _uiState.value

        val urlError = (SettingsValidator.validateBaseUrl(current.baseUrl) as? Validation.Invalid)?.message
        val portError = (SettingsValidator.validatePort(current.portText) as? Validation.Invalid)?.message

        if (urlError != null || portError != null) {
            _uiState.update { it.copy(baseUrlError = urlError, portError = portError) }
            return
        }

        _uiState.update { it.copy(isSaving = true, message = null) }

        viewModelScope.launch {
            val port = current.portText.trim().toIntOrNull() ?: ProxySettings.DEFAULT_PORT
            val settings = ProxySettings(
                baseUrl = SettingsValidator.normalizeBaseUrl(current.baseUrl),
                apiKey = current.apiKey.trim(),
                port = port,
            )

            val outcome = runCatching { settingsRepository.save(settings) }

            outcome.fold(
                onSuccess = {
                    // Keeps a running server on the newly configured port
                    // without the user having to cycle it by hand.
                    runCatching { proxyServer.restartIfPortChanged(port) }
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            baseUrl = settings.baseUrl,
                            apiKey = settings.apiKey,
                            maskedKey = SettingsValidator.maskApiKey(settings.apiKey),
                            portText = port.toString(),
                            message = "Saved.",
                            messageIsError = false,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            message = "Could not save: ${error.message}",
                            messageIsError = true,
                        )
                    }
                },
            )
        }
    }

    /**
     * Verifies the configuration end to end by calling `GET /v1/models`
     * upstream with the key currently in the form (not the saved one), so the
     * user can test before committing.
     */
    fun testConnection() {
        val current = _uiState.value
        val urlError = (SettingsValidator.validateBaseUrl(current.baseUrl) as? Validation.Invalid)?.message
        if (urlError != null) {
            _uiState.update { it.copy(baseUrlError = urlError) }
            return
        }
        if (current.apiKey.isBlank()) {
            _uiState.update {
                it.copy(message = "Enter an API key first.", messageIsError = true)
            }
            return
        }

        _uiState.update { it.copy(isTesting = true, message = null) }

        viewModelScope.launch {
            val base = SettingsValidator.normalizeBaseUrl(current.baseUrl)
            val result = withContext(ioDispatcher) {
                runCatching {
                    // Derive a short-timeout client from the shared one so the
                    // connection pool is reused but a hung host cannot stall
                    // the UI indefinitely.
                    val probe = httpClient.newBuilder()
                        .callTimeout(20, TimeUnit.SECONDS)
                        .readTimeout(20, TimeUnit.SECONDS)
                        .build()
                    val request = Request.Builder()
                        .url("$base/v1/models")
                        .header("Authorization", "Bearer ${current.apiKey.trim()}")
                        .get()
                        .build()
                    probe.newCall(request).execute().use { it.code }
                }
            }

            _uiState.update { state ->
                val (message, isError) = result.fold(
                    onSuccess = { code ->
                        when {
                            code == 401 || code == 403 ->
                                "Upstream reached, but rejected the key (HTTP $code)." to true
                            code >= 400 ->
                                "Upstream reached and returned HTTP $code." to true
                            else -> "Success: HTTP $code. Configuration looks good." to false
                        }
                    },
                    onFailure = { error ->
                        val reason = if (error is IOException) {
                            error.message ?: error.javaClass.simpleName
                        } else {
                            error.message ?: "unknown error"
                        }
                        "Could not reach the upstream: $reason" to true
                    },
                )
                state.copy(isTesting = false, message = message, messageIsError = isError)
            }
        }
    }

    /**
     * Irreversible recovery path: wipes stored values *and* the wrapping key.
     *
     * Exists because both backends can end up in a state where every read fails
     * - an invalidated Keystore key, or the `EncryptedSharedPreferences` keyset
     * corruption that shows up on some OEM images. Without this the user's only
     * option is clearing app data from system settings.
     */
    fun resetSecureStorage() {
        viewModelScope.launch {
            runCatching { store.destroyAll() }
            settingsRepository.clearApiKey()
            _uiState.update {
                it.copy(
                    apiKey = "",
                    maskedKey = SettingsValidator.maskApiKey(""),
                    message = "Secure storage reset. Re-enter your API key.",
                    messageIsError = false,
                )
            }
        }
    }
}
