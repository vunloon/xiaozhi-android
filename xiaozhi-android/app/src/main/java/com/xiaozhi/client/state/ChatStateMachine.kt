package com.xiaozhi.client.state

import android.util.Log
import com.xiaozhi.client.audio.AudioCapture
import com.xiaozhi.client.audio.AudioPlayer
import com.xiaozhi.client.audio.OpusCodec
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.core.protocol.WebSocketProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Drives the IDLE ⇄ LISTENING ⇄ SPEAKING state machine and binds the audio
 * pipeline to the WebSocket.
 *
 * Mirrors `py-xiaozhi/src/application.py` `Application` (states `IDLE`,
 * `CONNECTING`, `LISTENING`, `SPEAKING`) but with a manual push-to-talk
 * trigger only — wake-word is a phase-4 add.
 *
 * Responsibilities:
 *   - Start/stop [AudioCapture] when entering/leaving LISTENING.
 *   - Start/stop [AudioPlayer] on TTS start/stop messages.
 *   - Forward Opus frames from [AudioCapture] to [WebSocketProtocol.sendAudio].
 *   - Surface a [transcript] flow for the UI (user + assistant bubbles).
 */
class ChatStateMachine(
    private val protocol: WebSocketProtocol,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ChatState.IDLE)
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var framePipeJob: Job? = null
    private var protocolEventJob: Job? = null

    /** Begin observing the protocol. Idempotent. */
    fun bind() {
        if (protocolEventJob?.isActive == true) return
        ensurePipeline()
        protocolEventJob = scope.launch {
            protocol.events.collectLatest { ev -> handleEvent(ev) }
        }
    }

    /** Push-to-talk: starts a manual listen turn. */
    fun startListening() {        val s = _state.value
        if (s == ChatState.LISTENING) return
        Log.i(TAG, "startListening (from $s)")
        // If we're currently speaking, abort the TTS first.
        if (s == ChatState.SPEAKING) {
            protocol.sendAbort()
            stopPlayback()
        }
        _state.value = ChatState.LISTENING
        protocol.sendStartListening(mode = ListenMode.manual)
        startCapture()
    }

    /** Push-to-talk release: ends the listen turn. */
    fun stopListening() {
        if (_state.value != ChatState.LISTENING) return
        Log.i(TAG, "stopListening")
        _state.value = ChatState.IDLE
        protocol.sendStopListening()
        scope.launch { stopCapture() }
    }

    /**
     * Send a typed text utterance. Adds the text to the transcript immediately
     * and asks the protocol to deliver it as a complete listen cycle. The
     * server runs it through LLM and replies via TTS — same flow as voice,
     * just without STT.
     */
    fun sendText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        Log.i(TAG, "sendText: '$trimmed'")
        addUser(trimmed)
        protocol.sendTextUtterance(trimmed)
    }

    /** Hard-stop everything (e.g. user navigates away). */
    fun shutdown() {
        framePipeJob?.cancel()
        framePipeJob = null
        protocolEventJob?.cancel()
        protocolEventJob = null
        scope.launch {
            stopCapture()
            stopPlayback()
        }
        _state.value = ChatState.IDLE
    }

    // ------------------------------------------------------------------------

    private fun handleEvent(ev: WebSocketProtocol.ServerEvent) {
        val detail = when (ev) {
            is WebSocketProtocol.ServerEvent.Stt -> "text='${ev.text}'"
            is WebSocketProtocol.ServerEvent.TtsStart -> "text=${ev.text}"
            is WebSocketProtocol.ServerEvent.AudioFrame -> "bytes=${ev.bytes.size}"
            else -> ""
        }
        Log.d(TAG, "event: ${ev::class.simpleName} $detail")
        when (ev) {
            is WebSocketProtocol.ServerEvent.TtsStart -> {
                _state.value = ChatState.SPEAKING
                startPlayback()
                ev.text?.takeIf { it.isNotBlank() }?.let { addAssistant(it) }
            }
            WebSocketProtocol.ServerEvent.TtsStop -> {
                stopPlayback()
                if (_state.value == ChatState.SPEAKING) _state.value = ChatState.IDLE
            }
            is WebSocketProtocol.ServerEvent.Stt -> {
                // The server echoes back the exact text we sent in text mode,
                // and the user bubble is already added by sendText(). Dedupe
                // to avoid a double entry (our sent text + STT echo). For
                // voice, stt is the only source and always gets added.
                val last = _transcript.value.lastOrNull()
                if (last !is TranscriptEntry.User || last.text != ev.text) {
                    addUser(ev.text)
                }
            }
            is WebSocketProtocol.ServerEvent.Llm -> Unit
            is WebSocketProtocol.ServerEvent.Hello -> Unit
            is WebSocketProtocol.ServerEvent.Mcp -> Unit
            is WebSocketProtocol.ServerEvent.IotCommands -> Unit
            WebSocketProtocol.ServerEvent.Goodbye -> _state.value = ChatState.IDLE
            is WebSocketProtocol.ServerEvent.AudioFrame -> Unit
            is WebSocketProtocol.ServerEvent.TtsSentenceStart -> {
                // api.tenclass.net sends the assistant's spoken text on
                // sentence_start (tts.start carries no text) — surface it in
                // the transcript.
                ev.text?.takeIf { it.isNotBlank() }?.let { addAssistant(it) }
            }
            is WebSocketProtocol.ServerEvent.TtsSentenceEnd -> Unit
            is WebSocketProtocol.ServerEvent.Error -> Log.e(TAG, "protocol error", ev.throwable)
        }
    }

    private fun ensurePipeline() {
        if (player == null) {
            player = AudioPlayer(scope = scope).also { protocol.attachPlayer(it) }
        }
        if (capture == null) {
            capture = AudioCapture(scope = scope).also { cap ->
                framePipeJob = scope.launch {
                    cap.frames.collectLatest { packet ->
                        protocol.sendAudio(packet)
                    }
                }
            }
        }
    }

    private fun startCapture() {
        if (!OpusCodec.isAvailable) {
            Log.e(TAG, "Cannot start capture — opus native lib not loaded")
            return
        }
        capture?.start()
    }

    private suspend fun stopCapture() {
        capture?.stop()
    }

    private fun startPlayback() {
        player?.start()
    }

    private fun stopPlayback() {
        player?.pause()
    }

    private fun addUser(text: String) {
        _transcript.value = _transcript.value + TranscriptEntry.User(text)
    }

    private fun addAssistant(text: String) {
        _transcript.value = _transcript.value + TranscriptEntry.Assistant(text)
    }

    enum class ChatState { IDLE, LISTENING, SPEAKING }

    sealed interface TranscriptEntry {
        val text: String
        data class User(override val text: String) : TranscriptEntry
        data class Assistant(override val text: String) : TranscriptEntry
    }

    companion object {
        private const val TAG = "ChatStateMachine"
    }
}
