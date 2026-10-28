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
 * Supports the three listening modes of the browser client (`protocol.ts`):
 *  - MANUAL: push-to-talk — hold to listen, release to send.
 *  - AUTO:   tap to start a hands-free turn; the conversation keeps itself
 *            alive (the app re-issues `listen start` after the assistant
 *            finishes speaking) until the user taps stop.
 *  - REALTIME: full-duplex — the mic stays live even while the assistant is
 *            speaking so the user can interrupt at any moment.
 *
 * The `sessionActive` flag mirrors the web's conversation liveness. Audio is
 * captured whenever [shouldCapture] holds: LISTENING, or SPEAKING in realtime
 * mode.
 */
class ChatStateMachine(
    private val protocol: WebSocketProtocol,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(ChatState.IDLE)
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _listeningMode = MutableStateFlow(ListenMode.manual)
    val listeningMode: StateFlow<ListenMode> = _listeningMode.asStateFlow()

    private val _sessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = _sessionActive.asStateFlow()

    private val _emotion = MutableStateFlow("neutral")
    val emotion: StateFlow<String> = _emotion.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<TranscriptEntry>> = _transcript.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private var capture: AudioCapture? = null
    private var player: AudioPlayer? = null
    private var framePipeJob: Job? = null
    private var micLevelJob: Job? = null
    private var protocolEventJob: Job? = null

    /** Begin observing the protocol. Idempotent. */
    fun bind() {
        ensurePipeline()
        if (protocolEventJob?.isActive != true) {
            protocolEventJob = scope.launch {
                // Mirror the web's onClosed: a lost socket ends any active session
                // (the server forgets the listen state across reconnects, so a
                // stale LISTENING would silently stop recording forever).
                launch {
                    protocol.state.collect { s ->
                        if (s is WebSocketProtocol.ConnectionState.Disconnected ||
                            s is WebSocketProtocol.ConnectionState.Failed
                        ) {
                            if (_state.value != ChatState.IDLE || _sessionActive.value) {
                                Log.i(TAG, "connection lost — resetting session state")
                                _state.value = ChatState.IDLE
                                _sessionActive.value = false
                                stopPlayback()
                                stopCapture()
                            }
                        }
                    }
                }
                protocol.events.collectLatest { ev -> handleEvent(ev) }
            }
        }
        // shutdown() (ChatViewModel.onCleared) cancels the frame/level pipes;
        // re-arm them here — capture/player live in the app-scoped container
        // and survive activity restarts, so these jobs must be recreated even
        // when the pipeline objects already exist. Otherwise the mic encodes
        // frames that are silently never sent to the server.
        val cap = capture ?: return
        if (framePipeJob?.isActive != true) {
            framePipeJob = scope.launch {
                cap.frames.collectLatest { packet ->
                    protocol.sendAudio(packet)
                }
            }
        }
        if (micLevelJob?.isActive != true) {
            micLevelJob = scope.launch {
                cap.level.collectLatest { _micLevel.value = it }
            }
        }
    }

    fun setMode(mode: ListenMode) {
        if (_listeningMode.value == mode) return
        Log.i(TAG, "setMode -> $mode")
        // Switching modes mid-conversation: manual is a one-shot turn, so if we
        // were in an active auto/realtime session, end it.
        if (mode == ListenMode.manual && _sessionActive.value) {
            endConversation()
        }
        _listeningMode.value = mode
    }

    /** Start a listen turn. In manual mode this is push-to-talk; in auto/
     *  realtime it opens a hands-free session. */
    fun startListening(mode: ListenMode? = null) {
        val m = mode ?: _listeningMode.value
        if (m != ListenMode.manual) _sessionActive.value = true
        _listeningMode.value = m
        val s = _state.value
        if (s == ChatState.LISTENING) return
        Log.i(TAG, "startListening ($m) from $s")
        if (s == ChatState.SPEAKING) {
            protocol.sendAbort()
            stopPlayback()
        }
        _state.value = ChatState.LISTENING
        protocol.sendStartListening(mode = m)
        startCapture()
    }

    /** Push-to-talk release: ends the manual listen turn. */
    fun stopListening() {
        if (_state.value != ChatState.LISTENING) return
        Log.i(TAG, "stopListening")
        _state.value = ChatState.IDLE
        _sessionActive.value = false
        protocol.sendStopListening()
        scope.launch { stopCapture() }
    }

    /** Ends a hands-free (auto/realtime) conversation. Web parity: only a
     *  `listen stop` is sent — no abort (the server ends the session cleanly). */
    fun endConversation() {
        if (!_sessionActive.value && _state.value != ChatState.LISTENING) return
        Log.i(TAG, "endConversation")
        _sessionActive.value = false
        protocol.sendStopListening()
        stopPlayback()
        _state.value = ChatState.IDLE
        scope.launch { stopCapture() }
    }

    /** Interrupt the assistant. Keeps the session alive in auto/realtime:
     *  the mic simply resumes streaming and the server's VAD picks up the
     *  next utterance (web parity — no `listen start` re-send here). */
    fun abort() {
        Log.i(TAG, "abort")
        protocol.sendAbort()
        stopPlayback()
        if (_sessionActive.value) {
            _state.value = ChatState.LISTENING
            startCapture()
        } else {
            _state.value = ChatState.IDLE
            scope.launch { stopCapture() }
        }
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
        micLevelJob?.cancel()
        micLevelJob = null
        protocolEventJob?.cancel()
        protocolEventJob = null
        scope.launch {
            stopCapture()
            stopPlayback()
        }
        _state.value = ChatState.IDLE
        _sessionActive.value = false
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
                // Realtime: keep the mic live during the reply so the user can
                // barge in at any moment. Other modes pause capture.
                if (!shouldCapture()) scope.launch { stopCapture() }
            }
            WebSocketProtocol.ServerEvent.TtsStop -> {
                stopPlayback()
                when {
                    _sessionActive.value -> {
                        // Hands-free session: go back to listening. The web
                        // re-issues listen start unless realtime mic is live.
                        _state.value = ChatState.LISTENING
                        if (_listeningMode.value != ListenMode.realtime || capture?.isActive != true) {
                            protocol.sendStartListening(mode = _listeningMode.value)
                        }
                        startCapture()
                    }
                    _state.value == ChatState.SPEAKING -> _state.value = ChatState.IDLE
                }
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
            is WebSocketProtocol.ServerEvent.Llm -> {
                ev.emotion?.takeIf { it.isNotBlank() }?.let { _emotion.value = it }
            }
            is WebSocketProtocol.ServerEvent.Hello -> Unit
            is WebSocketProtocol.ServerEvent.Mcp -> Unit
            is WebSocketProtocol.ServerEvent.IotCommands -> Unit
            WebSocketProtocol.ServerEvent.Goodbye -> {
                _state.value = ChatState.IDLE
                _sessionActive.value = false
            }
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

    /** Whether the mic should be capturing right now. */
    private fun shouldCapture(): Boolean =
        _state.value == ChatState.LISTENING ||
            (_state.value == ChatState.SPEAKING && _sessionActive.value && _listeningMode.value == ListenMode.realtime)

    private fun ensurePipeline() {
        if (player == null) {
            player = AudioPlayer(scope = scope).also { protocol.attachPlayer(it) }
        }
        if (capture == null) {
            capture = AudioCapture(scope = scope)
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