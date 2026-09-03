package dev.chanooh.alert.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.chanooh.alert.alarm.CriticalAlarmService
import dev.chanooh.alert.alarm.UrgentAlertService
import dev.chanooh.alert.network.AckWorker
import dev.chanooh.alert.security.AlertVerifier
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class AlertDispatcher(private val context: Context) {
    private val deduplicator = EventDeduplicator(context)
    private val activeStore = ActiveAlertStore(context)

    suspend fun handle(event: AlertEvent) {
        val settings = SettingsRepository(context).settings.first()
        val secret = SecretStore(context).getDeviceHmacSecret()
        if (!AlertVerifier.verify(event, settings.deviceId, secret)) return

        val isFirstDelivery = deduplicator.tryReserve(event.id)
        if (!isFirstDelivery) {
            // CRITICAL events remain in ActiveAlertStore until the user ACKs.
            // If HyperOS/root kills the app while the alarm is active, the
            // server retry must be able to re-arm the alarm after Guardian
            // restores MQTT instead of being swallowed by durable dedupe.
            if (event.level == AlertLevel.CRITICAL && activeStore.contains(event.id)) {
                ensureCriticalAlarm(event, settings)
            }
            return
        }

        AlertHistoryStore.record(context, event)

        try {
            when (event.level) {
                AlertLevel.CRITICAL -> {
                    activeStore.add(event.id)
                    try {
                        ensureCriticalAlarm(event, settings)
                    } catch (error: Throwable) {
                        activeStore.remove(event.id)
                        throw error
                    }
                }
                AlertLevel.URGENT -> {
                    UrgentAlertService.start(
                        context = context,
                        title = event.title,
                        message = event.message,
                        volumePercent = settings.criticalVolumePercent,
                        silentMode = settings.silentModeEnabled
                    )
                    AckWorker.enqueue(context, event.id)
                    AlertHistoryStore.markAcknowledged(context, listOf(event.id))
                }
                AlertLevel.WARNING -> {
                    showNotification(event, NotificationManager.IMPORTANCE_DEFAULT, vibrate = true)
                    AckWorker.enqueue(context, event.id)
                    AlertHistoryStore.markAcknowledged(context, listOf(event.id))
                }
                AlertLevel.INFO -> {
                    showNotification(event, NotificationManager.IMPORTANCE_LOW, vibrate = false)
                    AckWorker.enqueue(context, event.id)
                    AlertHistoryStore.markAcknowledged(context, listOf(event.id))
                }
            }
        } catch (error: Throwable) {
            deduplicator.forget(event.id)
            throw error
        }
    }

    private fun ensureCriticalAlarm(event: AlertEvent, settings: AppSettings) {
        UrgentAlertService.stop(context)
        CriticalAlarmService.start(
            context = context,
            title = event.title,
            message = event.message,
            volumePercent = settings.criticalVolumePercent,
            restoreVolume = settings.restoreVolumeAfterAck,
            rootDndOverride = settings.rootDndOverrideEnabled,
            silentMode = settings.silentModeEnabled
        )
    }

    private fun showNotification(event: AlertEvent, importance: Int, vibrate: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "alert_${event.level.name.lowercase()}"
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                when (event.level) {
                    AlertLevel.INFO -> "Info 通知"
                    AlertLevel.WARNING -> "Warning 告警"
                    AlertLevel.URGENT -> "Urgent 告警"
                    AlertLevel.CRITICAL -> "Critical 告警"
                },
                importance
            ).apply {
                enableVibration(vibrate)
                if (vibrate) vibrationPattern = longArrayOf(0, 250, 150, 450)
            }
        )
        manager.notify(
            event.id.hashCode(),
            Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(event.title)
                .setContentText(event.message)
                .setStyle(Notification.BigTextStyle().bigText(event.message))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .build()
        )
    }
}
