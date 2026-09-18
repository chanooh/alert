package dev.chanooh.alert.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import dev.chanooh.alert.alert.AlertDispatcher
import dev.chanooh.alert.alert.AlertEvent
import dev.chanooh.alert.alert.DispatchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Invoked only by the KernelSU module after it durably writes an inbox item. */
class RootIngressService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Alert:RootIngress")
            .apply { setReferenceCounted(false); acquire(MAX_PROCESSING_MS) }
        scope.launch {
            try {
                val dispatcher = AlertDispatcher(applicationContext)
                RootTransportFiles.pendingEvents(applicationContext).forEach { entry ->
                    val event = runCatching { AlertEvent.fromJson(entry.readBytes()) }.getOrNull()
                    if (event == null) {
                        RootTransportFiles.deleteInboxEntry(entry)
                        return@forEach
                    }
                    when (runCatching { dispatcher.handle(event) }.getOrNull()) {
                        DispatchResult.PROCESSED, DispatchResult.DUPLICATE ->
                            RootTransportFiles.deleteInboxEntry(entry)
                        DispatchResult.REJECTED ->
                            RootTransportFiles.quarantineRejectedInboxEntry(applicationContext, entry, event.id)
                        null -> Unit // Leave it durable for Guardian's bounded retry.
                    }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "告警接收", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                setShowBadge(false)
            }
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("告警通道")
            .setContentText("正在处理新告警")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_DRAIN = "dev.chanooh.alert.action.ROOT_DRAIN"
        private const val CHANNEL_ID = "root_ingress"
        private const val NOTIFICATION_ID = 8002
        private const val MAX_PROCESSING_MS = 60_000L
    }
}
