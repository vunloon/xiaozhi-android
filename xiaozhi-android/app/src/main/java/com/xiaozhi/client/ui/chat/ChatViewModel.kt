package com.xiaozhi.client.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaozhi.client.AppContainer
import com.xiaozhi.client.audio.OpusCodec
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import com.xiaozhi.client.state.ChatStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val machine: ChatStateMachine = container.chatStateMachine

    val connectionState: StateFlow<WebSocketProtocol.ConnectionState> = container.protocol.state

    val chatState: StateFlow<ChatStateMachine.ChatState> = machine.state

    val listeningMode: StateFlow<com.xiaozhi.client.core.protocol.ListenMode> = machine.listeningMode

    val sessionActive: StateFlow<Boolean> = machine.sessionActive

    val emotion: StateFlow<String> = machine.emotion

    val transcript: StateFlow<List<ChatStateMachine.TranscriptEntry>> = machine.transcript

    /** Normalized mic level 0..1 (0 when idle). */
    val micLevel: StateFlow<Float> = machine.micLevel

    private val _opusAvailable = MutableStateFlow(OpusCodec.isAvailable)
    val opusAvailable: StateFlow<Boolean> = _opusAvailable.asStateFlow()

    private val _toolDrawerOpen = MutableStateFlow(false)
    val toolDrawerOpen: StateFlow<Boolean> = _toolDrawerOpen.asStateFlow()

    init {
        machine.bind()
        // Always run OTA once on entering chat — even if the activation screen
        // was skipped, OTA refreshes the websocket URL/token (and matches the
        // py-xiaozhi flow which POSTs OTA before every connect).
        if (container.activationManager.state.value is com.xiaozhi.client.core.activation.ActivationState.Idle ||
            container.activationManager.state.value is com.xiaozhi.client.core.activation.ActivationState.Failed
        ) {
            container.appScope.launch {
                val otaUrl = container.settingsStore.first().otaUrl
                container.activationManager.start(otaUrl = otaUrl)
            }
        }
        ensureConnection()
    }

    fun connect() = ensureConnection()

    fun reconnect() {
        container.protocol.disconnect()
        container.protocol.connect()
    }

    fun toggleToolDrawer() {
        _toolDrawerOpen.value = !_toolDrawerOpen.value
    }

    fun setMode(mode: com.xiaozhi.client.core.protocol.ListenMode) = machine.setMode(mode)
    fun startListening() = machine.startListening()
    fun stopListening() = machine.stopListening()
    fun endConversation() = machine.endConversation()
    fun abort() = machine.abort()
    fun sendText(text: String) = machine.sendText(text)

    private fun ensureConnection() {
        if (connectionState.value is WebSocketProtocol.ConnectionState.Disconnected ||
            connectionState.value is WebSocketProtocol.ConnectionState.Failed
        ) {
            // Prefer the user's Settings override for ws URL/token; otherwise
            // fall back to the websocket config returned by OTA, if any; and
            // finally to the protocol's built-in default (api.tenclass.net).
            container.appScope.launch {
                val s = container.settingsStore.first()
                val url = s.wsUrl.takeIf { it.isNotBlank() }
                val token = s.token.takeIf { it.isNotBlank() }
                val ota = container.activationManager.websocketConfig.value
                val effectiveUrl = url ?: ota?.url
                val effectiveToken = token ?: ota?.token
                container.protocol.connect(url = effectiveUrl, token = effectiveToken)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        machine.shutdown()
    }
}
