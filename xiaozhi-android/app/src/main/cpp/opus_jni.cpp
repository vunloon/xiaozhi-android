// opus_jni.cpp — thin JNI bridge over libopus for Android.
//
// Mirrors the minimal surface used by `py-xiaozhi/src/audio_codecs/audio_codec.py`:
//   - OpusEncoder: VOIP application, 16 kHz mono, 20 ms frames (320 samples in)
//   - OpusDecoder: 24 kHz mono (server TTS output rate)
//
// The Kotlin side keeps encoder/decoder pointers as `Long` fields. We expose
// create/encode/destroy (encoder) and create/decode/destroy (decoder) plus
// a "reset" for encoder reset on session start.

#include <jni.h>
#include <android/log.h>
#include <opus.h>
#include <cstring>
#include <cstdlib>
#include <vector>

#define TAG "xiaozhi-opus-jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)

namespace {

inline opus_int32 application_from_int(jint app) {
    switch (app) {
        case 0: return OPUS_APPLICATION_VOIP;
        case 1: return OPUS_APPLICATION_AUDIO;
        case 2: return OPUS_APPLICATION_RESTRICTED_LOWDELAY;
        default: return OPUS_APPLICATION_VOIP;
    }
}

// Pass libopus error codes through unchanged — Kotlin side switches on the
// raw value (negative = error code, positive = byte/sample count).
inline jint opus_result(jint n) { return n; }

}  // namespace

extern "C" {

// ----------------------------------------------------------------------------
// Encoder
// ----------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeEncoderCreate(
        JNIEnv* env, jclass, jint sampleRate, jint channels, jint application) {
    opus_int32 err = 0;
    OpusEncoder* enc = opus_encoder_create(
        sampleRate, channels, application_from_int(application), &err);
    if (err != OPUS_OK || enc == nullptr) {
        LOGE("opus_encoder_create failed: %d", err);
        return 0;
    }
    return reinterpret_cast<jlong>(enc);
}

JNIEXPORT void JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeEncoderDestroy(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr == 0) return;
    opus_encoder_destroy(reinterpret_cast<OpusEncoder*>(ptr));
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeEncode(
        JNIEnv* env, jclass, jlong ptr,
        jshortArray input, jint frameSize, jbyteArray output, jint maxOutBytes) {
    if (ptr == 0) return -100;
    auto* enc = reinterpret_cast<OpusEncoder*>(ptr);

    jshort* in_buf = env->GetShortArrayElements(input, nullptr);
    if (in_buf == nullptr) return -3;

    std::vector<unsigned char> out_buf(maxOutBytes);
    opus_int32 n = opus_encode(
        enc,
        reinterpret_cast<const opus_int16*>(in_buf),
        frameSize,
        out_buf.data(),
        static_cast<opus_int16>(maxOutBytes));

    env->ReleaseShortArrayElements(input, in_buf, JNI_ABORT);

    if (n < 0) {
        return opus_result(n);
    }
    // Copy back to Java.
    env->SetByteArrayRegion(output, 0, n,
                            reinterpret_cast<const jbyte*>(out_buf.data()));
    return n;
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeEncoderSetBitrate(
        JNIEnv*, jclass, jlong ptr, jint bitrateBps) {
    if (ptr == 0) return -100;
    return opus_encoder_ctl(reinterpret_cast<OpusEncoder*>(ptr),
                            OPUS_SET_BITRATE(bitrateBps));
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeEncoderResetState(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr == 0) return -100;
    return opus_encoder_ctl(reinterpret_cast<OpusEncoder*>(ptr),
                            OPUS_RESET_STATE);
}

// ----------------------------------------------------------------------------
// Decoder
// ----------------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeDecoderCreate(
        JNIEnv*, jclass, jint sampleRate, jint channels) {
    opus_int32 err = 0;
    OpusDecoder* dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == nullptr) {
        LOGE("opus_decoder_create failed: %d", err);
        return 0;
    }
    return reinterpret_cast<jlong>(dec);
}

JNIEXPORT void JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeDecoderDestroy(
        JNIEnv*, jclass, jlong ptr) {
    if (ptr == 0) return;
    opus_decoder_destroy(reinterpret_cast<OpusDecoder*>(ptr));
}

JNIEXPORT jint JNICALL
Java_com_xiaozhi_client_audio_OpusCodec_nativeDecode(
        JNIEnv* env, jclass, jlong ptr,
        jbyteArray input, jint inputLen,
        jshortArray output, jint maxOutSamples) {
    if (ptr == 0) return -100;
    auto* dec = reinterpret_cast<OpusDecoder*>(ptr);

    // The input may be null for packet-loss concealment.
    std::vector<unsigned char> in_buf;
    const unsigned char* in_ptr = nullptr;
    if (input != nullptr && inputLen > 0) {
        in_buf.resize(static_cast<size_t>(inputLen));
        env->GetByteArrayRegion(input, 0, inputLen,
                                 reinterpret_cast<jbyte*>(in_buf.data()));
        in_ptr = in_buf.data();
    }

    // Decode into a scratch buffer, then commit to the Java side via
    // SetShortArrayRegion (cleaner than two GetShortArrayElements calls).
    std::vector<opus_int16> out_buf(static_cast<size_t>(maxOutSamples));

    int n = opus_decode(
        dec,
        in_ptr,
        static_cast<opus_int32>(inputLen),
        out_buf.data(),
        maxOutSamples,
        /*decode_fec=*/0);

    if (n > 0) {
        env->SetShortArrayRegion(output, 0, n,
                                  reinterpret_cast<const jshort*>(out_buf.data()));
    }
    return opus_result(n);
}

}  // extern "C"
