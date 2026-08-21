package com.xiaozhi.client.core.protocol

import android.content.Context
import android.util.Log
import com.xiaozhi.client.BuildConfig
import com.xiaozhi.client.audio.AudioPlayer
import com.xiaozhi.client.core.identity.EfuseStore
import com.xiaozhi.client.iot.ThingManager
import com.xiaozhi.client.mcp.McpDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebSocket client for the xiaozhi device-session protocol.
 *
 * End-to-end flow mirrors `py-xiaozhi/src/protocols/websocket_protocol.py`:
 *  1. Open WSS with 4 headers (Authorization Bearer, Protocol-Version 1,
 *     Device-Id MAC, Client-Id UUID).
 *  2. Send `hello` with audio_params + features.mcp=true.
 *  3. Text frames = JSON control messages; binary frames = Opus audio.
 *  4. Auto-reconnect with exponential backoff.
 *
 * Tool calls (MCP envelopes) are surfaced as [mcpEvents] so the UI drawer can
 * show them. They are NOT executed here — Plan 1 has no in-app tools; tools
 * fire on the user's REMOTE MCP server and the cloud relays the activity.
 */
class WebSocketProtocol(
    private val context: Context,
    private val efuseStore: EfuseStore,
    private val sessionStore: SessionStore,
    private val thingManager: ThingManager,
    private val httpClient: OkHttpClient,
    private val mcpDispatcher: McpDispatcher,
) {

    private val scope = CoroutineScope(SupervisorJob())

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events.asSharedFlow()

    /** MCP tool-call activity forwarded from your remote MCP server. */
    private val _mcpEvents = MutableSharedFlow<JsonElement>(extraBufferCapacity = 16)
    val mcpEvents: SharedFlow<JsonElement> = _mcpEvents.asSharedFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var attachedPlayer: AudioPlayer? = null
    @Volatile private var currentConfig: Pair<String, String> = DEFAULT_URL to DEFAULT_TOKEN
    @Volatile private var manualDisconnect = false
    @Volatile private var reconnectAttempts = 0

    fun attachPlayer(player: AudioPlayer?) {
        attachedPlayer = player
    }

    /**
     * Open the connection. Defaults to the official endpoint. When the OTA
     * handshake returned a custom websocket.url + token (e.g. for a 3rd-party
     * server), pass them here.
     */
    fun connect(
        url: String? = null,
        token: String? = null,
    ) {
        val effectiveUrl = url?.takeIf { it.isNotBlank() } ?: currentConfig.first
        val effectiveToken = token?.takeIf { it.isNotBlank() } ?: currentConfig.second
        currentConfig = effectiveUrl to effectiveToken
        manualDisconnect = false
        scope.launch { openSocket(effectiveUrl, effectiveToken) }
    }

    fun disconnect() {
        manualDisconnect = true
        socket?.close(NORMAL_CLOSURE, "client disconnect")
        socket = null
        _state.value = ConnectionState.Disconnected
        sessionStore.clear()
    }

    // ------------------------------------------------------------------------
    // Outbound control messages
    // ------------------------------------------------------------------------

    fun sendStartListening(mode: ListenMode = ListenMode.manual) {
        send(ListenMessage(state = ListenState.start, mode = mode, sessionId = sessionStore.sessionId.value))
    }

    fun sendStopListening() {
        send(ListenMessage(state = ListenState.stop, sessionId = sessionStore.sessionId.value))
    }

    /**
     * Send a text utterance directly, bypassing STT. The xiaozhi servers
     * (including `api.tenclass.net`) treat a listen message with
     * `state:"detect"` + `text` as recognised speech and run it through the
     * LLM/TTS pipeline (`startToChat`), skipping ASR entirely.
     *
     * Use this for text-based chat (typing instead of speaking) and for
     * debugging the protocol path without mic capture.
     */
    fun sendTextUtterance(text: String) {
        send(ListenMessage(state = ListenState.detect, text = text, sessionId = sessionStore.sessionId.value))
    }

    fun sendAbort(reason: String? = null) {
        send(AbortMessage(reason = reason, sessionId = sessionStore.sessionId.value))
    }

    fun sendDetect(text: String) {
        send(ListenMessage(state = ListenState.detect, text = text, sessionId = sessionStore.sessionId.value))
    }

    /** Binary Opus frame. Returns true if queued on the socket. */
    fun sendAudio(opus: ByteArray): Boolean {
        val s = socket ?: return false
        return s.send(ByteString.of(*opus))
    }

    fun sendIotDescriptors() {
        send(IotDescriptorsMessage(sessionId = sessionStore.sessionId.value))
    }

    fun sendIotStates() {
        send(IotStatesMessage(sessionId = sessionStore.sessionId.value))
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    private fun openSocket(url: String, token: String) {
        if (socket != null) return
        _state.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Protocol-Version", "1")
            .header("Device-Id", efuseStore.macAddress)
            .header("Client-Id", efuseStore.clientId)
            .header("User-Agent", USER_AGENT)
            .build()

        val client = if (BuildConfig.TRUST_ALL_CERTS) trustAllClient() else httpClient
        socket = client.newWebSocket(request, SocketListener())
    }

    private fun onOpen() {
        reconnectAttempts = 0
        send(HelloMessage(audioParams = AudioParams(sampleRate = 16_000, channels = 1, frameDuration = 20)))
        _state.value = ConnectionState.Connected
    }

    private fun send(message: Any) {
        val s = socket ?: run {
            Log.w(TAG, "send: socket is null, dropping ${message::class.simpleName}")
            return
        }
        val raw = when (message) {
            is HelloMessage -> json.encodeToString(HelloMessage.serializer(), message)
            is ListenMessage -> json.encodeToString(ListenMessage.serializer(), message)
            is AbortMessage -> json.encodeToString(AbortMessage.serializer(), message)
            is IotDescriptorsMessage -> json.encodeToString(IotDescriptorsMessage.serializer(), message)
            is IotStatesMessage -> json.encodeToString(IotStatesMessage.serializer(), message)
            is McpEnvelope -> json.encodeToString(McpEnvelope.serializer(), message)
            else -> {
                Log.w(TAG, "send: unknown message type ${message::class}")
                return
            }
        }
        Log.d(TAG, "--> text: $raw")
        if (!s.send(raw)) {
            Log.w(TAG, "send: enqueue failed for ${message::class.simpleName}")
        }
    }

    private fun handleText(raw: String) {
        Log.d(TAG, "<-- text: ${raw.take(400)}")
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: run {
            Log.w(TAG, "dropping non-JSON text frame: ${raw.take(120)}")
            return
        }
        val type = obj[KEY_TYPE]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "hello" -> parse<ServerHelloMessage>(obj)?.let {
                sessionStore.onServerHello(it)
                _events.tryEmit(ServerEvent.Hello(it))
            }
            "tts" -> parse<TtsMessage>(obj)?.let { msg ->
                val ev = when (msg.state) {
                    TtsState.start -> ServerEvent.TtsStart(msg.text)
                    TtsState.stop -> ServerEvent.TtsStop
                    TtsState.sentence_start -> ServerEvent.TtsSentenceStart(msg.text)
                    TtsState.sentence_end -> ServerEvent.TtsSentenceEnd(msg.text)
                }
                _events.tryEmit(ev)
            }
            "stt" -> parse<SttMessage>(obj)?.let { _events.tryEmit(ServerEvent.Stt(it.text)) }
            "llm" -> parse<LlmMessage>(obj)?.let {
                _events.tryEmit(ServerEvent.Llm(emotion = it.emotion, text = it.text))
            }
            "mcp" -> parse<McpEnvelope>(obj)?.let { env ->
                _mcpEvents.tryEmit(env.payload)
                _events.tryEmit(ServerEvent.Mcp(env.payload))
                // Reply to JSON-RPC requests so the cloud's MCP handshake
                // completes. Without this the session never fully establishes
                // and the LLM never picks up the mic.
                handleMcpRequest(env.payload)
            }
            "iot" -> parse<IotCommandsMessage>(obj)?.let {
                _events.tryEmit(ServerEvent.IotCommands(it.commands))
                thingManager.handleCommands(it.commands) { changedStates ->
                    send(IotStatesMessage(states = changedStates, sessionId = sessionStore.sessionId.value))
                }
            }
            "goodbye" -> {
                _events.tryEmit(ServerEvent.Goodbye)
                disconnect()
            }
            else -> Log.d(TAG, "unknown message type=$type")
        }
    }

    /**
     * Responds to inbound MCP JSON-RPC requests from the cloud's MCP broker.
     * Delegates to the in-app [McpDispatcher], which runs the local tools
     * (news/klse/watchlist/music) and answers initialize/ping/tools/list/
     * tools/call. Replies go back through the voice channel as `mcp` envelopes.
     */
    private fun handleMcpRequest(payload: JsonElement) {
        val obj = runCatching { payload.jsonObject }.getOrNull() ?: return
        val method = obj[KEY_METHOD]?.jsonPrimitive?.contentOrNull ?: return
        if (method.startsWith("notifications")) return
        if (obj[KEY_ID] == null) return
        scope.launch {
            val reply = mcpDispatcher.handle(obj)
            if (reply != null) {
                send(McpEnvelope(payload = reply, sessionId = sessionStore.sessionId.value))
            }
        }
    }

    private fun sendMcpResult(id: JsonElement?, result: JsonObject) {
        if (id == null) return
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("result", result)
        }
        send(McpEnvelope(payload = payload, sessionId = sessionStore.sessionId.value))
    }

    private fun sendMcpError(id: JsonElement, error: JsonObject) {
        val payload = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", id)
            put("error", error)
        }
        send(McpEnvelope(payload = payload, sessionId = sessionStore.sessionId.value))
    }

    private fun handleBytes(bytes: ByteString) {
        val arr = bytes.toByteArray()
        attachedPlayer?.enqueueFrame(arr)
        _events.tryEmit(ServerEvent.AudioFrame(arr))
    }

    private inline fun <reified T> parse(obj: JsonObject): T? =
        runCatching { json.decodeFromString(serializer<T>(), obj.toString()) }
            .onFailure { Log.w(TAG, "parse ${T::class.simpleName} failed", it) }
            .getOrNull()

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        scope.launch {
            val attempt = ++reconnectAttempts
            val delayMs = (1_000L shl attempt.coerceAtMost(5)) // 2s, 4s, 8s, 16s, 32s…
            Log.i(TAG, "reconnect attempt $attempt in ${delayMs}ms")
            kotlinx.coroutines.delay(delayMs)
            if (!manualDisconnect) openSocket(currentConfig.first, currentConfig.second)
        }
    }

    private fun trustAllClient(): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        }
        return httpClient.newBuilder()
            .sslSocketFactory(sslCtx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open")
            this@WebSocketProtocol.onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleText(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handleBytes(bytes)

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // OkHttp's RealWebSocket.close refuses reserved codes (1004, 1005,
            // 1006) — xiaozhi's broker closes with 1005 ("no status received").
            // Sanitize before echoing so we don't kill the reader thread with
            // IllegalArgumentException. See RFC 6455 §7.4.
            val safeCode = when (code) {
                1000, 1001, 1002, 1003 -> code
                1004, 1005, 1006 -> 1000           // reserved — must not be sent
                in 1007..2999 -> code              // IANA-registered
                in 3000..4999 -> code              // app-defined
                else -> 1000
            }
            if (safeCode == code) {
                webSocket.close(code, reason)
            } else {
                Log.w(TAG, "server closed with reserved code $code — echoing 1000 instead")
                webSocket.close(1000, null)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "WebSocket closed: $code/$reason")
            socket = null
            _state.value = ConnectionState.Disconnected
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure", t)
            socket = null
            _state.value = ConnectionState.Failed(t.message ?: t::class.simpleName.orEmpty())
            _events.tryEmit(ServerEvent.Error(t))
            scheduleReconnect()
        }
    }

    sealed interface ConnectionState {
        data object Disconnected : ConnectionState
        data object Connecting : ConnectionState
        data object Connected : ConnectionState
        data class Failed(val reason: String) : ConnectionState
    }

    sealed interface ServerEvent {
        data class Hello(val message: ServerHelloMessage) : ServerEvent
        data class TtsStart(val text: String?) : ServerEvent
        data object TtsStop : ServerEvent
        data class TtsSentenceStart(val text: String?) : ServerEvent
        data class TtsSentenceEnd(val text: String?) : ServerEvent
        data class Stt(val text: String) : ServerEvent
        data class Llm(val emotion: String?, val text: String?) : ServerEvent
        data class Mcp(val payload: JsonElement) : ServerEvent
        data class IotCommands(val commands: List<JsonObject>) : ServerEvent
        data object Goodbye : ServerEvent
        data class AudioFrame(val bytes: ByteArray) : ServerEvent
        data class Error(val throwable: Throwable) : ServerEvent
    }

    companion object {
        private const val TAG = "WebSocketProtocol"
        private const val KEY_TYPE = "type"
        private const val KEY_ID = "id"
        private const val KEY_METHOD = "method"
        private const val NORMAL_CLOSURE = 1000
        const val DEFAULT_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        const val DEFAULT_TOKEN = "test-token"
        private const val USER_AGENT = "bread-compact-wifi/xiaozhi-android-1.0.0"
    }
}
