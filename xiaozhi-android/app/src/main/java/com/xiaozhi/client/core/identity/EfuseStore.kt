package com.xiaozhi.client.core.identity

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Persistent device identity — the on-device equivalent of
 * `py-xiaozhi/config/efuse.json`. Stored in EncryptedSharedPreferences so
 * the HMAC key (used to sign activation challenges) is not in plain text.
 *
 * Schema:
 *  - mac_address        "aa:bb:cc:dd:ee:ff"  (locally-administered, minted once)
 *  - serial_number      "SN-XXXXXXXX-xxxxxxxxxxxx"
 *  - hmac_key           sha256(hostname ‖ mac ‖ ANDROID_ID) hex
 *  - client_id          UUIDv4
 *  - activation_status  Boolean
 */
class EfuseStore(private val context: Context) {

    private val prefs = run {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (t: Throwable) {
            // Fallback for emulators / older keychain drivers.
            MasterKey.Builder(context, FALLBACK_ALIAS)
                .setKeyGenParameterSpec(
                    KeyGenParameterSpec.Builder(
                        FALLBACK_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                .build()
        }

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val macAddress: String
        get() = cached?.mac ?: ensureIdentity().mac

    val serialNumber: String
        get() = cached?.serial ?: ensureIdentity().serial

    val hmacKey: String
        get() = cached?.hmac ?: ensureIdentity().hmac

    val clientId: String
        get() = cached?.clientId ?: ensureIdentity().clientId

    var isActivated: Boolean
        get() = prefs.getBoolean(KEY_ACTIVATED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ACTIVATED, value).apply()
            cached = cached?.copy(activated = value)
        }

    @Volatile private var cached: Identity? = null

    @Synchronized
    private fun ensureIdentity(): Identity {
        cached?.let { return it }
        val existing = readIdentityFromPrefs()
        if (existing != null) {
            cached = existing
            return existing
        }
        val minted = mintIdentity(context)
        prefs.edit()
            .putString(KEY_MAC, minted.mac)
            .putString(KEY_SERIAL, minted.serial)
            .putString(KEY_HMAC, minted.hmac)
            .putString(KEY_CLIENT_ID, minted.clientId)
            .putBoolean(KEY_ACTIVATED, false)
            .apply()
        cached = minted
        return minted
    }

    private fun readIdentityFromPrefs(): Identity? {
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        return Identity(
            mac = mac,
            serial = prefs.getString(KEY_SERIAL, null) ?: return null,
            hmac = prefs.getString(KEY_HMAC, null) ?: return null,
            clientId = prefs.getString(KEY_CLIENT_ID, null) ?: return null,
            activated = prefs.getBoolean(KEY_ACTIVATED, false),
        )
    }

    /**
     * Serializes the device identity in the same shape the web client uses
     * (`{mac, clientId, serialNumber, hmacKey}`) so a linked device can be
     * moved between the browser, the phone, and py-xiaozhi configs.
     */
    fun exportJson(): String {
        val id = ensureIdentity()
        return "{\"mac\":\"${id.mac}\",\"clientId\":\"${id.clientId}\"," +
            "\"serialNumber\":\"${id.serial}\",\"hmacKey\":\"${id.hmac}\",\"activated\":${id.activated}}"
    }

    /**
     * Restores an identity exported by [exportJson] (or hand-built from a
     * py-xiaozhi config). Returns true on success. Fails if any required key
     * is missing or empty.
     */
    @Synchronized
    fun importJson(raw: String): Boolean {
        val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return false
        val mac = obj.optString("mac").takeIf { it.isNotBlank() } ?: return false
        val clientId = obj.optString("clientId").takeIf { it.isNotBlank() } ?: return false
        val serial = obj.optString("serialNumber").takeIf { it.isNotBlank() } ?: return false
        val hmac = obj.optString("hmacKey").takeIf { it.isNotBlank() } ?: return false
        prefs.edit()
            .putString(KEY_MAC, mac)
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_SERIAL, serial)
            .putString(KEY_HMAC, hmac)
            .putBoolean(KEY_ACTIVATED, obj.optBoolean("activated", false))
            .apply()
        cached = Identity(mac, serial, hmac, clientId, obj.optBoolean("activated", false))
        return true
    }

    /** Force-regenerate identity. Useful for testing or after a factory reset. */
    @Synchronized
    fun regenerate() {
        prefs.edit().clear().apply()
        cached = null
        ensureIdentity()
    }

    private data class Identity(
        val mac: String,
        val serial: String,
        val hmac: String,
        val clientId: String,
        val activated: Boolean = false,
    )

    companion object {
        private const val FILE_NAME = "xiaozhi_efuse"
        private const val KEY_MAC = "mac_address"
        private const val KEY_SERIAL = "serial_number"
        private const val KEY_HMAC = "hmac_key"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_ACTIVATED = "activation_status"
        private const val FALLBACK_ALIAS = "xiaozhi-efuse-master"

        private fun mintIdentity(context: Context): Identity {
            val rawMac = ByteArray(6).also { SecureRandom().nextBytes(it) }
            // Set locally-administered + unicast bits (RFC 7042) on byte 0.
            rawMac[0] = (rawMac[0].toInt() and 0xFC or 0x02).toByte()
            val mac = DeviceFingerprint.formatMac(rawMac)
            val serial = DeviceFingerprint.formatSerial(mac)
            val androidId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: "0000000000000000"
            val hostname = android.os.Build.MODEL ?: "android"
            val hmac = DeviceFingerprint.computeHmacKey(hostname, mac, androidId)
            val clientId = java.util.UUID.randomUUID().toString()
            return Identity(mac, serial, hmac, clientId, activated = false)
        }
    }
}
