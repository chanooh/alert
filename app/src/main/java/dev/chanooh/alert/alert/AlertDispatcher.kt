package dev.chanooh.alert.alert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.chanooh.alert.alarm.CriticalAlarmService
import dev.chanooh.alert.network.AckWorker
import dev.chanooh.alert.security.AlertVerifier
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class AlertDispatcher(private val context: Context) {
    private val deduplicator = EventDeduplicator(context)
    private val activeStore = ActiveAlertStore(context)

    suspend fun handle(event: AlertEvent) {
        val settings = SettingsRepository(context).settings.first()
        val secret = SecretStore(context).getDeviceHmacSecret()
        if (!AlertVerifier.verify(event, settings.deviceId, secret)) return
        if (deduplicator.seenOrMark(event.id)) return

        when (event.level) {
            AlertLevel.CRITICAL -> {
                activeStore.set(event.id)
                CriticalAlarmService.start(
                    context = context,
                    title = event.title,
                    message = event.message,
                    volumePercent = settings.criticalVolumePercent,
                    restoreVolume = settings.restoreVolumeAfterAck
                )
            }
            AlertLevel.URGENT -> {
                showNotification(event, NotificationManager.IMPORTANCE_HIGH)
                AckWorker.enqueue(context, event.id)
            }
            AlertLevel.WARNING -> {
                showNotification(event, NotificationManager.IMPORTANCE_DEFAULT)
                AckWorker.enqueue(context, event.id)
            }
            AlertLevel.INFO -> {
                showNotification(event, NotificationManager.IMPORTANCE_LOW)
                AckWorker.enqueue(context, event.id)
            }
        }
    }

    private fun showNotification(event: AlertEvent, importance: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "alert_${event.level.name.lowercase()}"
        manager.createNotificationChannel(
            NotificationChannel(channelId, event.level.name.lowercase().replaceFirstChar { it.uppercase() }, importance)
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
