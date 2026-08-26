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

    /** Set when the user asks to listen before the WebSocket is ready; fired
     *  once Connected (the web client likewise waits for hello on press). */
    private var pendingListen = false

    init {
        machine.bind()
        // Always run OTA once on entering chat — even if the activation screen
        // was skipped, OTA refreshes the websocket URL/token (and matches the
        // py-xiaozhi flow which POSTs OTA before every connect).
        if (container.activationManager.state.value is com.xiaozhi.client.core.activation.ActivationState.Idle ||
            container.activationManager.state.value is com.xiaozhi.client.core.activation.ActivationState.Failed
        ) {
            container.appScope.launch {
                container.activationManager.start()
            }
        }
        ensureConnection()
        viewModelScope.launch {
            connectionState.collect { s ->
                if (s is WebSocketProtocol.ConnectionState.Connected && pendingListen) {
                    pendingListen = false
                    machine.startListening()
                }
            }
        }
    }

    fun connect() = ensureConnection()

    /** True toggle like the web's power button: online → disconnect, else → connect. */
    fun toggleConnection() {
        if (connectionState.value is WebSocketProtocol.ConnectionState.Connected ||
            connectionState.value is WebSocketProtocol.ConnectionState.Connecting
        ) {
            container.protocol.disconnect()
        } else {
            ensureConnection()
        }
    }

    fun reconnect() {
        container.protocol.disconnect()
        container.protocol.connect()
    }

    fun toggleToolDrawer() {
        _toolDrawerOpen.value = !_toolDrawerOpen.value
    }

    fun setMode(mode: com.xiaozhi.client.core.protocol.ListenMode) {
        machine.setMode(mode)
        // Web parity: entering realtime mode opens the mic immediately (it is
        // an always-listening mode) when the connection is up.
        if (mode == com.xiaozhi.client.core.protocol.ListenMode.realtime &&
            connectionState.value is WebSocketProtocol.ConnectionState.Connected
        ) {
            machine.startListening(mode)
        }
    }

    fun startListening() {
        if (connectionState.value is WebSocketProtocol.ConnectionState.Connected) {
            machine.startListening()
        } else {
            pendingListen = true
            ensureConnection()
        }
    }

    fun stopListening() = machine.stopListening()
    fun endConversation() = machine.endConversation()
    fun abort() = machine.abort()
    fun sendText(text: String) = machine.sendText(text)

    private fun ensureConnection() {
        if (connectionState.value is WebSocketProtocol.ConnectionState.Disconnected ||
            connectionState.value is WebSocketProtocol.ConnectionState.Failed
        ) {
            // The websocket URL/token come from the OTA handshake; fall back
            // to the protocol's built-in default (api.tenclass.net) if OTA
            // hasn't answered yet.
            container.appScope.launch {
                val ota = container.activationManager.websocketConfig.value
                container.protocol.connect(url = ota?.url, token = ota?.token)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        machine.shutdown()
    }
}
