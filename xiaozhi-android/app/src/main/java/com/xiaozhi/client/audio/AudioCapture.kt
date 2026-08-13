package com.xiaozhi.client.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures microphone audio at 16 kHz mono PCM, encodes 20 ms frames to Opus,
 * and emits the compressed bytes through [frames].
 *
 * Mirrors the encoding side of `py-xiaozhi/src/audio_codecs/audio_codec.py`:
 *  - 16 kHz mono input
 *  - 20 ms frame duration (= 320 samples)
 *  - Opus APPLICATION_VOIP
 *  - default bitrate ~30 kbps (matches py-xiaozhi `OPUS_BITRATE = "voip"`)
 *
 * AudioRecord runs on a dedicated thread via [Dispatchers.Default]. The
 * captured/encoded frames are published to a hot [SharedFlow] — the
 * WebSocketProtocol subscribes and sends each as a binary frame.
 */
class AudioCapture(
    private val scope: CoroutineScope,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = 1,
    private val frameDurationMs: Int = 20,
) {

    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val frameSize: Int = (sampleRate * frameDurationMs / 1000) * channels
    private val pcmBuffer = ShortArray(frameSize)

    @Volatile private var record: AudioRecord? = null
    @Volatile private var encoder: OpusCodec.Encoder? = null
    private var captureJob: Job? = null

    @Volatile var isActive = false
        private set

    /**
     * Begin capturing. Idempotent — does nothing if already running.
     * Requires RECORD_AUDIO permission (caller's responsibility).
     */
    fun start() {
        if (isActive) return
        if (!OpusCodec.isAvailable) {
            Log.e(TAG, "Opus native lib not loaded — cannot capture")
            return
        }
        val encoder = this.encoder ?: OpusCodec.Encoder(
            sampleRate = sampleRate,
            channels = channels,
            application = OpusCodec.Application.VOIP,
        ).also { encoder = it }
        encoder.resetState()
        encoder.setBitrate(BITRATE_BPS)

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = (minBuf.coerceAtLeast(frameSize * 2) * 2)
            .coerceAtLeast(MIN_BUFFER_BYTES)

        val record = try {
            AudioRecord(
                SOURCE,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construct failed", t)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${record.state})")
            record.release()
            return
        }
        record.startRecording()
        this.record = record
        isActive = true
        Log.i(TAG, "capture started — sampleRate=$sampleRate frameSize=$frameSize bufferBytes=$bufferBytes")

        captureJob = scope.launch(Dispatchers.Default) {
            val outBuf = ByteArray(MAX_PACKET_BYTES)
            var framesEncoded = 0
            while (isActive && this@AudioCapture.isActive) {
                val read = record.read(pcmBuffer, 0, frameSize)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        Log.e(TAG, "AudioRecord.read returned $read — aborting capture")
                        break
                    }
                    continue
                }
                val n = encoder.encode(pcmBuffer, read, outBuf)
                if (n > 0) {
                    var peak = 0
                    for (i in 0 until read) {
                        val v = pcmBuffer[i].toInt()
                        if (v < 0) { kotlin.math.abs(v).let { if (it > peak) peak = it } }
                        else if (v > peak) peak = v
                    }
                    if (peak > 200) Log.d(TAG, "pcm peak=$peak frame=$framesEncoded packet=${n}B")
                    val packet = if (n == outBuf.size) outBuf.copyOf() else outBuf.copyOf(n)
                    // tryEmit to avoid blocking the audio thread — drop if buffer is full
                    // (better to drop one frame than stall capture).
                    _frames.tryEmit(packet)
                    framesEncoded++
                    if (framesEncoded % 50 == 0) {
                        Log.d(TAG, "encoded $framesEncoded frames, last packet=${n}B")
                    }
                }
            }
            Log.i(TAG, "capture loop exit — encoded $framesEncoded frames total")
        }
    }

    /** Stop capturing and release the mic. Safe to call repeatedly. */
    suspend fun stop() {
        isActive = false
        captureJob?.cancelAndJoin()
        captureJob = null
        record?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null
    }

    /** Permanent teardown. */
    fun release() {
        encoder?.close()
        encoder = null
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val SAMPLE_RATE = 16_000
        const val BITRATE_BPS = 30_000
        const val MIN_BUFFER_BYTES = 8 * 1024
        const val MAX_PACKET_BYTES = 1024

        // MIC (not VOICE_COMMUNICATION): the emulator's ranchu HAL only wires
        // the host microphone into AUDIO_SOURCE_MIC/default. VOICE_COMMUNICATION
        // routes to a virtual call path that is silent on the emulator.
        private const val SOURCE = MediaRecorder.AudioSource.MIC
    }
}
