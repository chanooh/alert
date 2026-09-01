package dev.chanooh.alert.security

import dev.chanooh.alert.alert.AlertEvent
import dev.chanooh.alert.alert.AlertLevel
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertVerifierTest {
    @Test
    fun acceptsMatchingDeviceAndSignature() {
        val event = signedEvent(deviceId = "device-test", secret = "test-secret")
        assertTrue(AlertVerifier.verify(event, "device-test", "test-secret"))
    }

    @Test
    fun rejectsWrongDeviceOrSignature() {
        val event = signedEvent(deviceId = "device-test", secret = "test-secret")
        assertFalse(AlertVerifier.verify(event, "other-device", "test-secret"))
        assertFalse(AlertVerifier.verify(event.copy(signature = "00".repeat(32)), "device-test", "test-secret"))
    }

    @Test
    fun rejectsStaleEvent() {
        val event = signedEvent(
            deviceId = "device-test",
            secret = "test-secret",
            createdAt = System.currentTimeMillis() - 16 * 60 * 1000L
        )
        assertFalse(AlertVerifier.verify(event, "device-test", "test-secret"))
    }

    private fun signedEvent(
        deviceId: String,
        secret: String,
        createdAt: Long = System.currentTimeMillis()
    ): AlertEvent {
        val unsigned = AlertEvent(
            id = "evt-test",
            deviceId = deviceId,
            level = AlertLevel.CRITICAL,
            title = "Critical test",
            message = "Signed payload",
            createdAt = createdAt,
            signature = ""
        )
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(unsigned.canonical().toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
        return unsigned.copy(signature = signature)
    }
}
