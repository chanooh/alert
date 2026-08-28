package dev.chanooh.alert.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.transport.MqttTransportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context.applicationContext).settings.first()
                if (settings.mqttEnabled) {
                    runCatching { MqttTransportService.start(context.applicationContext) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
