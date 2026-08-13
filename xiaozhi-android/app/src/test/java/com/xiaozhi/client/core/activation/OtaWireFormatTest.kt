package com.xiaozhi.client.core.activation

import com.xiaozhi.client.core.protocol.AudioParams
import com.xiaozhi.client.core.protocol.HelloMessage
import com.xiaozhi.client.core.protocol.ListenMessage
import com.xiaozhi.client.core.protocol.ListenMode
import com.xiaozhi.client.core.protocol.ListenState
import com.xiaozhi.client.core.protocol.TtsMessage
import com.xiaozhi.client.core.protocol.TtsState
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the JSON wire shapes we serialize match the snake_case
 * fields the xiaozhi server expects. Run alongside the message models to
 * catch accidental renaming regressions.
 */
class OtaWireFormatTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `HelloMessage emits snake_case audio_params`() {
        val wire = json.encodeToString(
            HelloMessage.serializer(),
            HelloMessage(audioParams = AudioParams(sampleRate = 16_000, frameDuration = 20)),
        )
        assertTrue("audio_params key must be snake_case", wire.contains("\"audio_params\""))
        assertTrue("sample_rate key must be snake_case", wire.contains("\"sample_rate\""))
        assertTrue("frame_duration key must be snake_case", wire.contains("\"frame_duration\""))
    }

    @Test
    fun `ListenMessage carries session_id and snake_case state values`() {
        val wire = json.encodeToString(
            ListenMessage.serializer(),
            ListenMessage(
                state = ListenState.start,
                mode = ListenMode.manual,
                sessionId = "abc-123",
            ),
        )
        assertTrue(wire.contains("\"session_id\":\"abc-123\""))
        assertTrue(wire.contains("\"state\":\"start\""))
        assertTrue(wire.contains("\"mode\":\"manual\""))
    }

    @Test
    fun `TtsMessage parses server start packet`() {
        // Real shape from py-xiaozhi session capture.
        val raw = """{"type":"tts","state":"start","text":"你好","session_id":"s-1"}"""
        val parsed = json.decodeFromString(TtsMessage.serializer(), raw)
        assertEquals(TtsState.start, parsed.state)
        assertEquals("你好", parsed.text)
        assertEquals("s-1", parsed.sessionId)
    }

    @Test
    fun `OtaResponse tolerates unknown fields like mqtt and server_time`() {
        val raw = """
            {"server_time":"2025-01-01T00:00:00Z",
             "websocket":{"url":"wss://api.tenclass.net/xiaozhi/v1/","token":"tok"},
             "mqtt":{"foo":"bar"}}
        """.trimIndent()
        val parsed = json.decodeFromString(OtaResponse.serializer(), raw)
        assertNotNull(parsed.websocket)
        assertEquals("wss://api.tenclass.net/xiaozhi/v1/", parsed.websocket?.url)
    }

    @Test
    fun `ActivationRequest payload key is capitalised Payload`() {
        // py-xiaozhi device_activator.py sends the body wrapped under "Payload"
        // (capital P) — verify we match.
        val req = ActivationRequest(
            Payload = ActivationPayload(
                serialNumber = "SN-ABCDEF-020b7404924e",
                challenge = "ch-1",
                hmac = "deadbeef",
            ),
        )
        val wire = json.encodeToString(ActivationRequest.serializer(), req)
        assertTrue(wire.contains("\"Payload\":{"))
        assertTrue(wire.contains("\"serial_number\":"))
        assertTrue(wire.contains("\"hmac\":\"deadbeef\""))
    }
}
