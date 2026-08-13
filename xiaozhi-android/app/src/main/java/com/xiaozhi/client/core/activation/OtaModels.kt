package com.xiaozhi.client.core.activation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * All wire DTOs for the OTA + v2-activation flow.
 *
 * Wire shapes mirror `py-xiaozhi/src/core/ota.py` and
 * `py-xiaozhi/src/utils/device_activator.py` exactly.
 */
@Serializable
data class OtaRequest(
    val application: OtaApplication,
    val board: OtaBoard,
)

@Serializable
data class OtaApplication(
    val version: String,
    @SerialName("elf_sha256") val elfSha256: String,
)

@Serializable
data class OtaBoard(
    val type: String,
    val name: String,
    val ip: String,
    val mac: String,
)

@Serializable
data class OtaResponse(
    @SerialName("server_time") val serverTime: JsonElement? = null,
    val firmware: JsonElement? = null,
    val websocket: OtaWebSocket? = null,
    val mqtt: JsonElement? = null,
    val activation: OtaActivation? = null,
)

@Serializable
data class OtaWebSocket(
    val url: String,
    val token: String,
)

@Serializable
data class OtaActivation(
    val challenge: String,
    val code: String,
    val message: String? = null,
    val `verify_url`: String? = null,
)

/** Body of the POST /xiaozhi/ota/activate request. */
@Serializable
data class ActivationRequest(
    val Payload: ActivationPayload,
)

@Serializable
data class ActivationPayload(
    val algorithm: String = "hmac-sha256",
    @SerialName("serial_number") val serialNumber: String,
    val challenge: String,
    val hmac: String,
)

/** Server response to /activate. Either fields are filled (success) or empty (202). */
@Serializable
data class ActivationResponse(
    val activated: Boolean = false,
    val message: String? = null,
)
