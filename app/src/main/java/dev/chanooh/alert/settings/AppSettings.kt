package dev.chanooh.alert.settings

data class AppSettings(
    val serverBaseUrl: String = "",
    val mqttBroker: String = "",
    val mqttUsername: String = "",
    val mqttEnabled: Boolean = false,
    val transportMode: TransportMode = TransportMode.APP_FALLBACK,
    val deviceId: String = "",
    val criticalVolumePercent: Int = 100,
    val restoreVolumeAfterAck: Boolean = true,
    val rootDndOverrideEnabled: Boolean = false,
    val silentModeEnabled: Boolean = false
)

enum class TransportMode {
    /** The original Android foreground MQTT client. Safe migration default. */
    APP_FALLBACK,
    /** KernelSU's native daemon owns the MQTT connection. */
    KERNELSU_ROOT
}

fun String.redacted(): String {
    if (isBlank()) return "Not configured"
    if (length <= 8) return "••••••••"
    return take(4) + "••••" + takeLast(4)
}
