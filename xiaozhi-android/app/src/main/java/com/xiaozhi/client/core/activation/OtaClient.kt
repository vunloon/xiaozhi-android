package com.xiaozhi.client.core.activation

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import com.xiaozhi.client.core.identity.EfuseStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Posts the OTA handshake and (if needed) the v2-activation challenge.
 *
 * Wire format mirrors `py-xiaozhi/src/core/ota.py` (`build_headers`, `build_payload`)
 * and `py-xiaozhi/src/utils/device_activator.py` (`activate` flow).
 *
 * NOTE: we do **not** parse the activation challenge HMAC verification URL with
 * our `hmac_key` here — we sign it server-side. We just need to send the
 * HMAC we computed locally with the same key.
 */
class OtaClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Perform the OTA handshake. Returns the parsed response. */
    @Throws(IOException::class)
    suspend fun ota(
        otaUrl: String,
        efuse: EfuseStore,
        appVersion: String = APP_VERSION,
        boardType: String = BOARD_TYPE,
    ): OtaResponse = withContext(Dispatchers.IO) {
        val body = OtaRequest(
            application = OtaApplication(version = appVersion, elfSha256 = efuse.hmacKey),
            board = OtaBoard(
                type = boardType,
                name = "xiaozhi-android",
                ip = lookupLanIp(),
                mac = efuse.macAddress,
            ),
        )
        val payload = json.encodeToString(OtaRequest.serializer(), body)

        val request = Request.Builder()
            .url(otaUrl)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .headersFor(efuse, appVersion)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("OTA HTTP ${resp.code}: ${resp.message}")
            }
            val raw = resp.body?.string()
                ?: throw IOException("OTA empty body")
            json.decodeFromString(OtaResponse.serializer(), raw)
        }
    }

    /**
     * Submits the HMAC-signed activation payload. Returns `activated=true`
     * when the server confirms activation, `false` when it returns 202
     * (user has not yet entered the code).
     *
     * Matches `py-xiaozhi/src/utils/device_activator.py`: any 2xx response
     * other than 202 means activated. The body is logged for diagnostics but
     * NOT used to decide activation — the server's status code is the source
     * of truth.
     */
    @Throws(IOException::class)
    suspend fun activate(
        activateUrl: String,
        efuse: EfuseStore,
        challenge: String,
        appVersion: String = APP_VERSION,
    ): ActivationResponse = withContext(Dispatchers.IO) {
        val hmacHex = hmacSha256Hex(efuse.hmacKey, challenge)
        val body = ActivationRequest(
            Payload = ActivationPayload(
                serialNumber = efuse.serialNumber,
                challenge = challenge,
                hmac = hmacHex,
            ),
        )
        val payload = json.encodeToString(ActivationRequest.serializer(), body)

        val request = Request.Builder()
            .url(activateUrl)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .headersFor(efuse, appVersion, activation = true)
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            android.util.Log.i(TAG, "activate HTTP ${resp.code}, body='${raw.take(200)}'")
            when {
                // 202 → user hasn't entered the code yet, keep polling.
                resp.code == 202 -> ActivationResponse(activated = false, message = "waiting-for-user")
                // Any other 2xx → activated (matches py-xiaozhi).
                resp.isSuccessful -> ActivationResponse(activated = true, message = raw.take(200))
                else -> throw IOException("Activate HTTP ${resp.code}: ${resp.message}")
            }
        }
    }

    private fun Request.Builder.headersFor(
        efuse: EfuseStore,
        appVersion: String,
        activation: Boolean = false,
    ): Request.Builder {
        addHeader("Device-Id", efuse.macAddress)
        addHeader("Client-Id", efuse.clientId)
        addHeader("Content-Type", "application/json")
        addHeader("User-Agent", "$BOARD_TYPE/xiaozhi-android-$appVersion")
        addHeader("Accept-Language", "zh-CN")
        if (activation) addHeader("Activation-Version", appVersion)
        return this
    }

    /** Best-effort IPv4 lookup (text form). Falls back to "0.0.0.0" when unavailable. */
    @Suppress("DEPRECATION")
    private fun lookupLanIp(): String = try {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
        if (ipInt != 0) Formatter.formatIpAddress(ipInt) else "0.0.0.0"
    } catch (t: Throwable) {
        "0.0.0.0"
    }

    private fun hmacSha256Hex(keyHex: String, message: String): String {
        // The HMAC key stored in efuse is sha256 output (hex). The server expects
        // us to sign with the *bytes* that produced that hex — i.e. the raw
        // 32-byte key. Decode the hex first, then sign.
        val keyBytes = hexToBytes(keyHex)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"))
        val result = mac.doFinal(message.toByteArray())
        return result.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { i ->
            val b = hex.substring(i * 2, i * 2 + 2).toInt(16)
            b.toByte()
        }
    }

    companion object {
        private const val TAG = "OtaClient"
        const val APP_VERSION = "1.0.0"
        const val BOARD_TYPE = "bread-compact-wifi"
        const val DEFAULT_OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        const val DEFAULT_WS_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        const val DEFAULT_WS_TOKEN = "test-token"
        const val DEFAULT_AUTH_URL = "https://xiaozhi.me/"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
