package com.xiaozhi.client.core.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * All wire types spoken over the xiaozhi device session WebSocket. Mirrors
 * the JSON shapes in `py-xiaozhi/src/protocols/protocol.py` and the
 * server→client handlers in `src/application.py` `_on_incoming_json`.
 *
 * Top-level discriminator is `type`. We model only the fields we read/write
 * — anything else falls into `Unknown` and is logged but ignored.
 *
 * Wire snake_case keys are mapped to camelCase Kotlin properties via
 * `@SerialName` so call sites read naturally.
 */

// ----------------------------------------------------------------------------
// Client → server
// ----------------------------------------------------------------------------

@Serializable
data class HelloMessage(
    val type: String = "hello",
    val version: Int = 1,
    val transport: String = "websocket",
    val features: HelloFeatures = HelloFeatures(),
    @SerialName("audio_params") val audioParams: AudioParams,
)

@Serializable
data class HelloFeatures(
    val mcp: Boolean = true,
    val iot: Boolean? = null,
)

@Serializable
data class AudioParams(
    val format: String = "opus",
    @SerialName("sample_rate") val sampleRate: Int,
    val channels: Int = 1,
    @SerialName("frame_duration") val frameDuration: Int,
)

@Serializable
data class ListenMessage(
    val type: String = "listen",
    val state: ListenState,
    @Serializable(with = ListenModeSerializer::class)
    val mode: ListenMode? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val text: String? = null,
)

@Serializable
enum class ListenState { detect, start, stop }

@Serializable
enum class ListenMode { realtime, auto, manual }

class ListenModeSerializer : KSerializer<ListenMode> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ListenMode", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ListenMode) =
        encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): ListenMode =
        runCatching { ListenMode.valueOf(decoder.decodeString()) }
            .getOrDefault(ListenMode.manual)
}

@Serializable
data class AbortMessage(
    val type: String = "abort",
    @SerialName("session_id") val sessionId: String? = null,
    val reason: String? = null,
)

@Serializable
data class IotDescriptorsMessage(
    val type: String = "iot",
    val update: Boolean = true,
    val descriptors: List<JsonElement> = emptyList(),
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class IotStatesMessage(
    val type: String = "iot",
    val update: Boolean = true,
    val states: List<JsonElement> = emptyList(),
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class McpEnvelope(
    val type: String = "mcp",
    val payload: JsonElement,
    @SerialName("session_id") val sessionId: String? = null,
)

// ----------------------------------------------------------------------------
// Server → client
// ----------------------------------------------------------------------------

@Serializable
data class ServerHelloMessage(
    val type: String = "hello",
    val transport: String? = null,
    @SerialName("audio_params") val audioParams: AudioParams? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class TtsMessage(
    val type: String = "tts",
    val state: TtsState,
    val text: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
enum class TtsState { start, stop, sentence_start, sentence_end }

@Serializable
data class SttMessage(
    val type: String = "stt",
    val text: String,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class LlmMessage(
    val type: String = "llm",
    val emotion: String? = null,
    val text: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class IotCommandsMessage(
    val type: String = "iot",
    val commands: List<JsonObject> = emptyList(),
    @SerialName("session_id") val sessionId: String? = null,
)

@Serializable
data class GoodbyeMessage(
    val type: String = "goodbye",
    @SerialName("session_id") val sessionId: String? = null,
)
