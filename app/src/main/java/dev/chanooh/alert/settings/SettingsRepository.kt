package dev.chanooh.alert.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "alert_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val serverBaseUrl = stringPreferencesKey("server_base_url")
        val mqttBroker = stringPreferencesKey("mqtt_broker")
        val mqttUsername = stringPreferencesKey("mqtt_username")
        val mqttEnabled = booleanPreferencesKey("mqtt_enabled")
        val deviceId = stringPreferencesKey("device_id")
        val criticalVolumePercent = intPreferencesKey("critical_volume_percent")
        val restoreVolumeAfterAck = booleanPreferencesKey("restore_volume_after_ack")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            serverBaseUrl = prefs[Keys.serverBaseUrl].orEmpty(),
            mqttBroker = prefs[Keys.mqttBroker].orEmpty(),
            mqttUsername = prefs[Keys.mqttUsername].orEmpty(),
            mqttEnabled = prefs[Keys.mqttEnabled] ?: false,
            deviceId = prefs[Keys.deviceId].orEmpty(),
            criticalVolumePercent = prefs[Keys.criticalVolumePercent] ?: 100,
            restoreVolumeAfterAck = prefs[Keys.restoreVolumeAfterAck] ?: true
        )
    }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.serverBaseUrl] = settings.serverBaseUrl.trim()
            prefs[Keys.mqttBroker] = settings.mqttBroker.trim()
            prefs[Keys.mqttUsername] = settings.mqttUsername.trim()
            prefs[Keys.mqttEnabled] = settings.mqttEnabled
            prefs[Keys.deviceId] = settings.deviceId.trim()
            prefs[Keys.criticalVolumePercent] = settings.criticalVolumePercent.coerceIn(10, 100)
            prefs[Keys.restoreVolumeAfterAck] = settings.restoreVolumeAfterAck
        }
    }
}
