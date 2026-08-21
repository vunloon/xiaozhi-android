package com.xiaozhi.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.core.settings.AppSettings
import com.xiaozhi.client.mcp.McpWssClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class IdentityInfo(
    val mac: String,
    val serial: String,
    val clientId: String,
    val activated: Boolean,
)

sealed interface McpWssUiState {
    data object Off : McpWssUiState
    data object Connecting : McpWssUiState
    data object Connected : McpWssUiState
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: Flow<AppSettings> = container.settingsStore.settings

    val mcpWssState: Flow<McpWssUiState> = container.mcpWssClient.state.map { s ->
        when (s) {
            McpWssClient.State.DISABLED -> McpWssUiState.Off
            McpWssClient.State.CONNECTING -> McpWssUiState.Connecting
            McpWssClient.State.CONNECTED -> McpWssUiState.Connected
        }
    }

    private val _toolActivity = MutableStateFlow<List<String>>(emptyList())
    val toolActivity: StateFlow<List<String>> = _toolActivity.asStateFlow()

    private val _identity = MutableStateFlow(readIdentity())
    val identity: StateFlow<IdentityInfo> = _identity.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _draft = MutableStateFlow<AppSettings?>(null)
    val draft: StateFlow<AppSettings?> = _draft.asStateFlow()

    init {
        // Resume a previously-enabled reverse-WSS server after app restart.
        viewModelScope.launch {
            val s = container.settingsStore.first()
            if (s.mcpWssEnabled) connectMcpWss()
        }
        viewModelScope.launch {
            container.protocol.mcpEvents.collect { payload ->
                _toolActivity.value = (_toolActivity.value + payload.toString()).takeLast(20)
            }
        }
    }

    fun loadDraft() {
        viewModelScope.launch {
            _draft.value = container.settingsStore.first()
        }
    }

    fun save(draft: AppSettings) {
        viewModelScope.launch {
            container.settingsStore.update { draft }
            _message.value = "Saved"
        }
    }

    fun setTheme(theme: com.xiaozhi.client.ui.theme.ThemeId) {
        _draft.value = _draft.value?.copy(theme = theme)
    }

    fun setField(transform: (AppSettings) -> AppSettings) {
        _draft.value = _draft.value?.let(transform)
    }

    fun toggleMcpWss() {
        viewModelScope.launch {
            if (container.mcpWssClient.state.value == McpWssClient.State.DISABLED) {
                connectMcpWss()
            } else {
                container.mcpWssClient.disconnect()
                container.settingsStore.update { it.copy(mcpWssEnabled = false) }
            }
        }
    }

    private suspend fun connectMcpWss() {
        val s = container.settingsStore.first()
        val token = s.token.takeIf { it.isNotBlank() } ?: ""
        val endpoint = s.mcpWssUrl.trim().trimEnd('?', '&') + token
        container.mcpWssClient.connect(endpoint)
        container.settingsStore.update { it.copy(mcpWssEnabled = true) }
    }

    fun copyIdentity(): String = container.efuseStore.exportJson()

    fun importIdentity(raw: String): Boolean {
        val ok = container.efuseStore.importJson(raw)
        if (ok) {
            _identity.value = readIdentity()
            _message.value = "Identity imported"
        } else {
            _message.value = "Import failed — check the JSON"
        }
        return ok
    }

    fun resetIdentity() {
        container.efuseStore.regenerate()
        _identity.value = readIdentity()
        _message.value = "Identity reset"
    }

    private fun readIdentity(): IdentityInfo {
        val e = container.efuseStore
        return IdentityInfo(
            mac = e.macAddress,
            serial = e.serialNumber,
            clientId = e.clientId,
            activated = e.isActivated,
        )
    }

    fun clearMessage() {
        _message.value = null
    }
}