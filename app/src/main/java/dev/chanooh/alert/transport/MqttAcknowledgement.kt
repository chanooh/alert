package dev.chanooh.alert.transport

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

object MqttAcknowledgement {
    fun payload(eventId: String, deviceId: String, secret: String): ByteArray {
        val acknowledgedAt = System.currentTimeMillis()
        val canonical = listOf(eventId, deviceId, acknowledgedAt.toString()).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return JSONObject()
            .put("id", eventId)
            .put("deviceId", deviceId)
            .put("acknowledgedAt", acknowledgedAt)
            .put("signature", signature)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}
