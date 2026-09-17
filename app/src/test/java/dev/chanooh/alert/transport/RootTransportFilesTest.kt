package dev.chanooh.alert.transport

import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.TransportMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RootTransportFilesTest {
    @Test
    fun `root configuration contains only mqtt routing credentials`() {
        val json = JSONObject(
            RootTransportFiles.rootConfigJson(
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
        )

        assertEquals(true, json.getBoolean("enabled"))
        assertEquals("device-test", json.getString("deviceId"))
        assertEquals("mqtt-password", json.getString("password"))
        assertFalse(json.has("hmacSecret"))
        assertFalse(json.has("deviceApiToken"))
    }

    @Test
    fun `fallback mode writes a disabled root configuration`() {
        val json = JSONObject(
            RootTransportFiles.rootConfigJson(
                AppSettings(mqttEnabled = true, transportMode = TransportMode.APP_FALLBACK, deviceId = "device-test"),
                mqttPassword = "mqtt-password",
                generation = 42
            )
        )
        assertFalse(json.getBoolean("enabled"))
        assertEquals("", json.getString("password"))
    }
}
