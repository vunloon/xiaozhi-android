package com.xiaozhi.client.core.identity

import java.security.MessageDigest

/**
 * Generates and formats the device-identity fields the xiaozhi server expects.
 *
 * Mirrors `py-xiaozhi/src/utils/device_fingerprint.py`. The Android app does
 * NOT see the hardware MAC address (Android hides it past API 11), so we
 * mint a stable locally-administered MAC at first run and persist it.
 */
object DeviceFingerprint {

    /** Stable locally-administered MAC in `aa:bb:cc:dd:ee:ff` form. */
    fun formatMac(rawSixBytes: ByteArray): String =
        rawSixBytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * `SN-<MD5(mac)[:8].upper()>-<mac-no-colons-lower>` — matches the official
     * client serial-number format so the device appears in xiaozhi.me with a
     * familiar shape.
     */
    fun formatSerial(macFormatted: String): String {
        val macNoColons = macFormatted.replace(":", "")
        val md5 = MessageDigest.getInstance("MD5").digest(macNoColons.toByteArray())
        val md5Head = md5.take(4).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return "SN-$md5Head-$macNoColons"
    }

    /**
     * `HMAC_key = sha256(hostname ‖ mac ‖ ANDROID_ID)` — used to sign the v2
     * activation challenge.
     */
    fun computeHmacKey(hostname: String, macFormatted: String, androidId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(hostname.toByteArray())
        md.update(0)
        md.update(macFormatted.toByteArray())
        md.update(0)
        md.update(androidId.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
