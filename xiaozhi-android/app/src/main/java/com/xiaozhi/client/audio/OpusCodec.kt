package com.xiaozhi.client.audio

import android.util.Log

/**
 * Thin Kotlin facade over the JNI Opus bindings.
 *
 * Mirrors the configuration used by `py-xiaozhi/src/audio_codecs/audio_codec.py`:
 *   - Encoder: 16 kHz mono, APPLICATION_VOIP, 20 ms frames → 320 samples in
 *   - Decoder: 24 kHz mono (server TTS rate) — but instantiated by the caller
 *     with whatever sample rate the server actually advertised.
 *
 * The native library `libopus` is shipped prebuilt under jniLibs/<abi>/.
 * `libopus_jni` (built by CMake via Gradle's externalNativeBuild) wraps it.
 *
 * The two native libraries are loaded once at process start; if either fails
 * to load we mark the codec unavailable so the UI can show a clear error
 * rather than crash.
 */
object OpusCodec {

    private const val TAG = "OpusCodec"

    /** Application enum passed to the native layer. */
    object Application {
        const val VOIP = 0
        const val AUDIO = 1
        const val RESTRICTED_LOWDELAY = 2
    }

    @Volatile private var loaded = false
    @Volatile private var loadError: Throwable? = null

    /** Attempt to load the native libs. Idempotent. */
    fun init() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary("opus")
                System.loadLibrary("opus_jni")
                loaded = true
                Log.i(TAG, "Native opus + opus_jni loaded")
            } catch (t: Throwable) {
                loadError = t
                Log.e(TAG, "Failed to load native opus libs", t)
            }
        }
    }

    val isAvailable: Boolean get() = loaded

    fun ensureLoaded() {
        check(loaded) {
            "Native opus libs are not loaded: ${loadError?.message ?: "init() not called"}"
        }
    }

    // ------------------------------------------------------------------------
    // Encoder
    // ------------------------------------------------------------------------

    /** RAII Opus encoder. Pass to [AudioCapture] to encode 16 kHz PCM frames. */
    class Encoder(
        sampleRate: Int = 16_000,
        channels: Int = 1,
        application: Int = Application.VOIP,
    ) : AutoCloseable {

        private val ptr: Long

        init {
            ensureLoaded()
            val p = nativeEncoderCreate(sampleRate, channels, application)
            check(p != 0L) { "opus_encoder_create failed (sr=$sampleRate ch=$channels app=$application)" }
            ptr = p
        }

        /**
         * Encode one frame of PCM input. The output buffer must be sized for the
         * max compressed packet size (typically 1024 bytes per frame for 16 k
         * mono VOIP). Returns the number of bytes written.
         */
        fun encode(input: ShortArray, frameSize: Int, output: ByteArray): Int {
            check(input.size >= frameSize) { "input shorter than frameSize" }
            return nativeEncode(ptr, input, frameSize, output, output.size)
        }

        fun setBitrate(bps: Int): Int = nativeEncoderSetBitrate(ptr, bps)
        fun resetState(): Int = nativeEncoderResetState(ptr)

        override fun close() {
            if (ptr != 0L) nativeEncoderDestroy(ptr)
        }
    }

    // ------------------------------------------------------------------------
    // Decoder
    // ------------------------------------------------------------------------

    /** RAII Opus decoder. Used by [AudioPlayer] to decode TTS frames. */
    class Decoder(
        sampleRate: Int,
        channels: Int = 1,
    ) : AutoCloseable {

        private val ptr: Long

        init {
            ensureLoaded()
            val p = nativeDecoderCreate(sampleRate, channels)
            check(p != 0L) { "opus_decoder_create failed (sr=$sampleRate ch=$channels)" }
            ptr = p
        }

        /**
         * Decode one Opus packet. Output buffer must hold at least the maximum
         * frame size in samples (e.g. 480 samples for 24 kHz × 20 ms).
         *
         * Pass `input = null` (or `inputLen = 0`) to invoke packet-loss
         * concealment when a frame is lost.
         */
        fun decode(input: ByteArray?, inputLen: Int, output: ShortArray, maxSamples: Int): Int =
            nativeDecode(ptr, input, inputLen, output, maxSamples)

        override fun close() {
            if (ptr != 0L) nativeDecoderDestroy(ptr)
        }
    }

    // ------------------------------------------------------------------------
    // JNI entry points
    // ------------------------------------------------------------------------

    @JvmStatic private external fun nativeEncoderCreate(
        sampleRate: Int, channels: Int, application: Int,
    ): Long

    @JvmStatic private external fun nativeEncoderDestroy(ptr: Long)
    @JvmStatic private external fun nativeEncode(
        ptr: Long, input: ShortArray, frameSize: Int, output: ByteArray, maxOutBytes: Int,
    ): Int
    @JvmStatic private external fun nativeEncoderSetBitrate(ptr: Long, bitrateBps: Int): Int
    @JvmStatic private external fun nativeEncoderResetState(ptr: Long): Int

    @JvmStatic private external fun nativeDecoderCreate(
        sampleRate: Int, channels: Int,
    ): Long

    @JvmStatic private external fun nativeDecoderDestroy(ptr: Long)
    @JvmStatic private external fun nativeDecode(
        ptr: Long, input: ByteArray?, inputLen: Int, output: ShortArray, maxSamples: Int,
    ): Int
}
