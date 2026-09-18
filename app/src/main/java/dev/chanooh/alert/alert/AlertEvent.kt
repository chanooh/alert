package dev.chanooh.alert.alert

import org.json.JSONObject

data class AlertEvent(
    val id: String,
    val deviceId: String,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val createdAt: Long,
    val signature: String
) {
    fun canonical(): String = listOf(
        id,
        deviceId,
        level.name.lowercase(),
        createdAt.toString(),
        title,
        message
    ).joinToString("\n")

    companion object {
        fun fromJson(bytes: ByteArray): AlertEvent {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            return AlertEvent(
                id = json.getString("id"),
                deviceId = json.getString("deviceId"),
                level = AlertLevel.valueOf(json.getString("level").uppercase()),
                title = json.getString("title"),
                message = json.getString("message"),
                createdAt = json.getLong("createdAt"),
                signature = json.getString("signature")
            )
        }
    }
}
