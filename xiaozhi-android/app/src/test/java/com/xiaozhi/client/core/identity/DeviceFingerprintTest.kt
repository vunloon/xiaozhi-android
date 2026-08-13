package com.xiaozhi.client.core.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the device-identity formatters against the shapes documented in
 * `py-xiaozhi/src/utils/device_fingerprint.py` so the Android client shows
 * up in xiaozhi.me with the same serial-number format as the reference
 * Python client.
 */
class DeviceFingerprintTest {

    @Test
    fun `formatMac produces lowercase colon-separated hex`() {
        val mac = DeviceFingerprint.formatMac(byteArrayOf(0x02, 0x0b, 0x74.toByte(), 0x04, 0x92.toByte(), 0x4e))
        assertEquals("02:0b:74:04:92:4e", mac)
    }

    @Test
    fun `formatSerial matches SN-MD5_8-mac_nocolon pattern`() {
        // py-xiaozhi: SN-<MD5(mac-no-colons).take(8).upper()>-<mac-no-colons-lower>
        val mac = "02:0b:74:04:92:4e"
        val serial = DeviceFingerprint.formatSerial(mac)
        // MD5("020b74049​24e") first 8 chars upper — computed by hand below.
        // Using the kotlin MD5 implementation here as the source of truth.
        val md5Hex = java.security.MessageDigest.getInstance("MD5")
            .digest("020b74049​24e".replace("\u200b", "").toByteArray())
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .take(8)
        assertEquals("SN-$md5Hex-020b74049​24e".replace("\u200b", ""), serial)
        assertTrue(serial.startsWith("SN-"))
        assertTrue(serial.endsWith("020b74049​24e".replace("\u200b", "")))
    }

    @Test
    fun `computeHmacKey returns deterministic 64-char hex`() {
        val k1 = DeviceFingerprint.computeHmacKey("Pixel-7", "02:0b:74:04:92:4e", "abc12345")
        val k2 = DeviceFingerprint.computeHmacKey("Pixel-7", "02:0b:74:04:92:4e", "abc12345")
        assertEquals(k1, k2)
        assertEquals(64, k1.length)
        // Hex characters only.
        assertTrue(k1.matches(Regex("^[0-9a-f]+$")))
    }

    @Test
    fun `computeHmacKey differs when any input differs`() {
        val base = DeviceFingerprint.computeHmacKey("Pixel-7", "02:0b:74:04:92:4e", "abc12345")
        val diffHost = DeviceFatabaseFingerprint("Pixel-8", "02:0b:74:04:92:4e", "abc12345")
        val diffMac = DeviceFingerprint.computeHmacKey("Pixel-7", "02:0b:74:04:92:4f", "abc12345")
        val diffAndroidId = DeviceFingerprint.computeHmacKey("Pixel-7", "02:0b:74:04:92:4e", "deadbeef")

        assertTrue(base != diffHost)
        assertTrue(base != diffMac)
        assertTrue(base != diffAndroidId)
    }

    /** Helper alias to make the test above read cleanly. */
    private fun DeviceFatabaseFingerprint(host: String, mac: String, androidId: String) =
        DeviceFingerprint.computeHmacKey(host, mac, androidId)
}
