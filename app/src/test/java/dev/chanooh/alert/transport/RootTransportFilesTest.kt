package dev.chanooh.alert.transport

import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.TransportMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootTransportFilesTest {
    @Test
    fun `root configuration contains only mqtt routing credentials`() {
        val json = RootTransportFiles.rootConfigJson(
            AppSettings(
                mqttBroker = "mqtt://broker.example:1883",
                mqttUsername = "mqtt-user",
                mqttEnabled = true,
                transportMode = TransportMode.KERNELSU_ROOT,
                deviceId = "device-test"
            ),
            mqttPassword = "mqtt-password",
            generation = 42
        )

        assertTrue(json.contains("\"enabled\":true"))
        assertTrue(json.contains("\"deviceId\":\"device-test\""))
        assertTrue(json.contains("\"password\":\"mqtt-password\""))
        assertFalse(json.contains("hmacSecret"))
        assertFalse(json.contains("deviceApiToken"))
    }

    @Test
    fun `fallback mode writes a disabled root configuration`() {
        val json = RootTransportFiles.rootConfigJson(
            AppSettings(mqttEnabled = true, transportMode = TransportMode.APP_FALLBACK, deviceId = "device-test"),
            mqttPassword = "mqtt-password",
            generation = 42
        )
        assertTrue(json.contains("\"enabled\":false"))
        assertTrue(json.contains("\"password\":\"\""))
    }
}
