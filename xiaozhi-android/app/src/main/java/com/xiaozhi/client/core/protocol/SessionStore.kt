package com.xiaozhi.client.core.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the `session_id` for the active xiaozhi session.
 *
 * The session id is sent to us in the server's `hello` and is then attached
 * to every client→server message. The server may rotate it; we always
 * prefer the most-recently-seen value.
 */
class SessionStore {

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _outputSampleRate = MutableStateFlow(DEFAULT_OUTPUT_SAMPLE_RATE)
    val outputSampleRate: StateFlow<Int> = _outputSampleRate.asStateFlow()

    private val _frameDurationMs = MutableStateFlow(DEFAULT_FRAME_DURATION_MS)
    val frameDurationMs: StateFlow<Int> = _frameDurationMs.asStateFlow()

    /** Apply a freshly received server `hello` to update session params. */
    fun onServerHello(msg: ServerHelloMessage) {
        msg.sessionId?.let { _sessionId.value = it }
        msg.audioParams?.let { p ->
            _outputSampleRate.value = p.sampleRate
            _frameDurationMs.value = p.frameDuration
        }
    }

    fun clear() {
        _sessionId.value = null
        _outputSampleRate.value = DEFAULT_OUTPUT_SAMPLE_RATE
        _frameDurationMs.value = DEFAULT_FRAME_DURATION_MS
    }

    companion object {
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 24_000
        const val DEFAULT_FRAME_DURATION_MS = 20
    }
}
