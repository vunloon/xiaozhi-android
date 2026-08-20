package com.xiaozhi.client.mcp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Reverse-WSS MCP channel, mirroring `xiaozhi-web/src/core/mcpWss.ts`.
 *
 * The remote xiaozhi server opens (or we open) a WebSocket to
 * `wss://api.xiaozhi.me/mcp/?token=<JWT>` and pushes MCP JSON-RPC *requests*
 * (tools/call, tools/list, initialize…) at this device. Each request is routed
 * through the shared [McpDispatcher] (the same one serving the voice channel)
 * and the reply is sent back over this socket.
 */
class McpWssClient(
    private val mcpDispatcher: McpDispatcher,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    enum class State { DISABLED, CONNECTING, CONNECTED }

    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(State.DISABLED)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var enabled = false
    @Volatile private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    @Volatile private var endpoint = ""

    /** Start the channel. `url` is the full endpoint (base + token). */
    fun connect(url: String) {
        endpoint = url
        enabled = true
        reconnectAttempts = 0
        _state.value = State.CONNECTING
        openSocket()
    }

    fun disconnect() {
        enabled = false
        reconnectJob?.cancel()
        socket?.close(1000, "client disconnect")
        socket = null
        _state.value = State.DISABLED
    }

    private fun openSocket() {
        if (!enabled || endpoint.isBlank()) return
        try {
            val request = Request.Builder().url(endpoint).build()
            socket = httpClient.newWebSocket(request, listener)
            // Note: OkHttp returns the socket synchronously but connection state
            // is reported asynchronously via onOpen/onFailure.
        } catch (t: Throwable) {
            Log.e(TAG, "reverse-WSS open failed", t)
            scheduleReconnect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "reverse-WSS connected: ${response.request.url}")
            reconnectAttempts = 0
            _state.value = State.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
            if (method.startsWith("notifications")) return
            val id = obj["id"] ?: return
            scope.launch {
                try {
                    val reply = mcpDispatcher.handle(obj)
                    if (reply != null) webSocket.send(reply.toString())
                } catch (t: Throwable) {
                    Log.w(TAG, "reverse-WSS request failed", t)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "reverse-WSS closed ($code): $reason")
            _state.value = State.CONNECTING
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "reverse-WSS failure: ${t.message}")
            if (enabled) _state.value = State.CONNECTING
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!enabled) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = ++reconnectAttempts
            val delayMs = (1_000L shl attempt.coerceAtMost(6)) // 1s, 2s, 4s… 64s cap
            Log.i(TAG, "reverse-WSS reconnect attempt $attempt in ${delayMs}ms")
            delay(delayMs)
            if (enabled) openSocket()
        }
    }

    companion object {
        private const val TAG = "McpWssClient"
    }
}