package dev.chanooh.alert.security

import dev.chanooh.alert.alert.AlertEvent
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AlertVerifier {
    /** Must match the server's bounded MQTT retry lifetime. */
    private const val MAX_DELIVERY_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_FUTURE_SKEW_MS = 5 * 60 * 1000L

    fun verify(
        event: AlertEvent,
        expectedDeviceId: String,
        secret: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (expectedDeviceId.isBlank() || secret.isBlank()) return false
        if (event.deviceId != expectedDeviceId) return false
        // Delivery can legitimately be delayed by a dead network or deep Doze.
        // Reject unsigned/tampered data as before, but accept a valid server event
        // throughout the same 24-hour retry window the server promises. Future
        // timestamps remain tightly bounded to prevent replay-window extension.
        val age = nowMillis - event.createdAt
        if (age > MAX_DELIVERY_AGE_MS || age < -MAX_FUTURE_SKEW_MS) return false

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
