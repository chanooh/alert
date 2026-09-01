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
import dev.chanooh.alert.settings.SettingsRepository
import kotlinx.coroutines.flow.first

class AlertDispatcher(private val context: Context) {
    private val deduplicator = EventDeduplicator(context)
    private val activeStore = ActiveAlertStore(context)

    suspend fun handle(event: AlertEvent) {
        val settings = SettingsRepository(context).settings.first()
        val secret = SecretStore(context).getDeviceHmacSecret()
        if (!AlertVerifier.verify(event, settings.deviceId, secret)) return
        if (!deduplicator.tryReserve(event.id)) return

        try {
            when (event.level) {
                AlertLevel.CRITICAL -> {
                    UrgentAlertService.stop(context)
                    activeStore.add(event.id)
                    try {
                        CriticalAlarmService.start(
                            context = context,
                            title = event.title,
                            message = event.message,
                            volumePercent = settings.criticalVolumePercent,
                            restoreVolume = settings.restoreVolumeAfterAck,
                            rootDndOverride = settings.rootDndOverrideEnabled
                        )
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
                        volumePercent = settings.criticalVolumePercent
                    )
                    AckWorker.enqueue(context, event.id)
                }
                AlertLevel.WARNING -> {
                    showNotification(event, NotificationManager.IMPORTANCE_DEFAULT, vibrate = true)
                    AckWorker.enqueue(context, event.id)
                }
                AlertLevel.INFO -> {
                    showNotification(event, NotificationManager.IMPORTANCE_LOW, vibrate = false)
                    AckWorker.enqueue(context, event.id)
                }
            }
        } catch (error: Throwable) {
            deduplicator.forget(event.id)
            throw error
        }
    }

    private fun showNotification(event: AlertEvent, importance: Int, vibrate: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "alert_${event.level.name.lowercase()}"
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                event.level.name.lowercase().replaceFirstChar { it.uppercase() },
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
