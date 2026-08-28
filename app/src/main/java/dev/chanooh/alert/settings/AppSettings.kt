package dev.chanooh.alert.settings

data class AppSettings(
    val serverBaseUrl: String = "",
    val mqttBroker: String = "",
    val mqttUsername: String = "",
    val mqttEnabled: Boolean = false,
    val deviceId: String = "",
    val criticalVolumePercent: Int = 100,
    val restoreVolumeAfterAck: Boolean = true
)

fun String.redacted(): String {
    if (isBlank()) return "Not configured"
    if (length <= 8) return "••••••••"
    return take(4) + "••••" + takeLast(4)
}
