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

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {

    val machine: ChatStateMachine = container.chatStateMachine

    val connectionState: StateFlow<WebSocketProtocol.ConnectionState> = container.protocol.state

    val chatState: StateFlow<ChatStateMachine.ChatState> = machine.state

    val transcript: StateFlow<List<ChatStateMachine.TranscriptEntry>> = machine.transcript

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
            container.activationManager.start()
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

    fun startListening() = machine.startListening()
    fun stopListening() = machine.stopListening()
    fun sendText(text: String) = machine.sendText(text)

    private fun ensureConnection() {
        if (connectionState.value is WebSocketProtocol.ConnectionState.Disconnected ||
            connectionState.value is WebSocketProtocol.ConnectionState.Failed
        ) {
            // Prefer websocket config returned by OTA, if any; otherwise the
            // protocol's built-in default (api.tenclass.net).
            val cfg = container.activationManager.websocketConfig.value
            container.protocol.connect(url = cfg?.url, token = cfg?.token)
        }
    }

    override fun onCleared() {
        super.onCleared()
        machine.shutdown()
    }
}
