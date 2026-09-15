package dev.chanooh.alert.security

import dev.chanooh.alert.alert.AlertEvent
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AlertVerifier {
    private const val MAX_CLOCK_SKEW_MS = 15 * 60 * 1000L

    fun verify(event: AlertEvent, expectedDeviceId: String, secret: String): Boolean {
        if (expectedDeviceId.isBlank() || secret.isBlank()) return false
        if (event.deviceId != expectedDeviceId) return false
        if (kotlin.math.abs(System.currentTimeMillis() - event.createdAt) > MAX_CLOCK_SKEW_MS) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val expected = mac.doFinal(event.canonical().toByteArray(Charsets.UTF_8)).toHex()
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            event.signature.lowercase().toByteArray(Charsets.US_ASCII)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
