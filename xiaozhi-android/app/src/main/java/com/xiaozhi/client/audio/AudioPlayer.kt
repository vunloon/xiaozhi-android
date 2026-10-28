package com.xiaozhi.client.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decodes Opus frames received from the xiaozhi TTS stream and plays them
 * back via [AudioTrack] at 24 kHz mono.
 *
 * Mirrors the playback side of `py-xiaozhi/src/audio_codecs/audio_codec.py`:
 *  - 24 kHz mono output (official server)
 *  - 20 ms frame duration (480 samples per frame)
 *  - Opus decode → AudioTrack PCM write
 *
 * Feeding contract: [enqueueFrame] is called from the WebSocket reader thread
 * for each binary frame received between `tts.state=start` and `tts.state=stop`.
 * The actual decoding/playback happens on a dedicated coroutine.
 */
class AudioPlayer(
    private val scope: CoroutineScope,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = 1,
    private val frameDurationMs: Int = 20,
) {

    private val maxSamplesPerFrame: Int = (sampleRate * frameDurationMs / 1000) * channels

    private var decoder: OpusCodec.Decoder? = null
    private var track: AudioTrack? = null
    @Volatile private var queue: Channel<ByteArray?>? = null
    private var pumpJob: Job? = null

    private val playing = AtomicBoolean(false)
    val isPlaying: Boolean get() = playing.get()

    fun start() {
        if (playing.get()) return
        ensureDecoder()

        // Persistent queue: created lazily on first start but kept alive across
        // pause() so frames enqueued from the WebSocket reader thread before
        // the state machine processes tts.start are never dropped.
        val channel = queue ?: Channel<ByteArray?>(capacity = FRAME_BUFFER_CAPACITY).also { queue = it }

        val track = buildTrack().also { it.play() }
        this.track = track
        playing.set(true)

        pumpJob = scope.launch(Dispatchers.Default) {
            val pcm = ShortArray(maxSamplesPerFrame)
            // Plain iteration (NOT consumeEach): consumeEach cancels the
            // channel once its loop ends, which would deadlock every playback
            // cycle after the first one (tts stop → pump cancel → next start).
            for (packet in channel) {
                ensureActive()
                if (packet == null) continue
                val n = decoder?.decode(packet, packet.size, pcm, maxSamplesPerFrame) ?: -1
                if (n > 0) {
                    track.write(pcm, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            }
        }
    }

    /** Enqueue one Opus frame for decoding/playback. Buffers even if not started. */
    fun enqueueFrame(opusPacket: ByteArray) {
        val channel = queue ?: Channel<ByteArray?>(capacity = FRAME_BUFFER_CAPACITY).also { queue = it }
        channel.trySend(opusPacket)
    }

    /** Pause playback and flush any queued frames. Keeps decoder alive. */
    fun pause() {
        playing.set(false)
        pumpJob?.cancel()
        pumpJob = null
        track?.run {
            try { pause() } catch (_: IllegalStateException) {}
            try { flush() } catch (_: IllegalStateException) {}
        }
        track = null
        // Drain + reset the queue so the next turn starts on a fresh channel.
        queue?.let { q ->
            while (q.tryReceive().isSuccess) Unit
            q.cancel()
        }
        queue = null
    }

    /** Permanent teardown — release the AudioTrack and decoder. */
    fun release() {
        pause()
        track?.release()
        track = null
        decoder?.close()
        decoder = null
    }

    private fun ensureDecoder() {
        if (decoder == null) {
            decoder = OpusCodec.Decoder(sampleRate = sampleRate, channels = channels)
        }
    }

    private fun buildTrack(): AudioTrack {
        val bufferBytes = (AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(maxSamplesPerFrame * 2) * 4)
            .coerceAtLeast(MIN_TRACK_BYTES)

        // USAGE_MEDIA: the media stream is what the volume rocker controls and
        // what the emulator reliably mixes to host audio. USAGE_VOICE_COMMUNICATION
        // works on real devices but its volume has no audible effect on the
        // emulator's ranchu HAL.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (t: Throwable) {
            // Older API levels may reject PERFORMANCE_MODE_LOW_LATENCY — fall back.
            Log.w(TAG, "Low-latency AudioTrack build failed, falling back", t)
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM,
            )
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
        const val SAMPLE_RATE = 24_000
        const val FRAME_BUFFER_CAPACITY = 32
        const val MIN_TRACK_BYTES = 8 * 1024
    }
}
