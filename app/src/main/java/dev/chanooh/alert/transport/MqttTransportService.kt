package dev.chanooh.alert.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import dev.chanooh.alert.alert.AlertDispatcher
import dev.chanooh.alert.alert.AlertEvent
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.system.GuardianMarker
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MqttTransportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Mqtt5AsyncClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                GuardianMarker.setEnabled(applicationContext, false)
                stopTransport()
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))
                scope.launch { connect() }
            }
        }
        return START_STICKY
    }

    private suspend fun connect() {
        val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.mqttEnabled || settings.mqttBroker.isBlank() || settings.deviceId.isBlank()) {
            GuardianMarker.setEnabled(applicationContext, false)
            stopTransport()
            return
        }
        GuardianMarker.setEnabled(applicationContext, true)

        runCatching { client?.disconnect()?.get(3, TimeUnit.SECONDS) }

        val uri = URI(settings.mqttBroker)
        val secure = uri.scheme.equals("mqtts", ignoreCase = true)
        require(uri.scheme.equals("mqtt", true) || secure) { "MQTT URI must use mqtt:// or mqtts://" }
        val host = requireNotNull(uri.host) { "MQTT host is missing" }
        val port = if (uri.port > 0) uri.port else if (secure) 8883 else 1883
        val topic = "alert/${settings.deviceId}/events"

        lateinit var mqtt: Mqtt5AsyncClient
        val builder = MqttClient.builder()
            .identifier("alert-${settings.deviceId}")
            .serverHost(host)
            .serverPort(port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                updateNotification("Armed · MQTT connected")
                mqtt.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback { publish ->
                        scope.launch {
                            runCatching {
                                AlertDispatcher(applicationContext)
                                    .handle(AlertEvent.fromJson(publish.payloadAsBytes))
                            }
                        }
                    }
                    .send()
                    .whenComplete { _, error ->
                        if (error != null) updateNotification("MQTT connected · subscription retrying")
                    }
            }
            .addDisconnectedListener {
                updateNotification("MQTT reconnecting")
            }

        if (secure) builder.sslWithDefaultConfig()
        mqtt = builder.useMqttVersion5().buildAsync()
        client = mqtt

        val connectBuilder = mqtt.connectWith()
            .cleanStart(false)
            .keepAlive(300)

        if (settings.mqttUsername.isNotBlank()) {
            connectBuilder.simpleAuth()
                .username(settings.mqttUsername)
                .password(SecretStore(applicationContext).getMqttPassword().toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }

        runCatching {
            connectBuilder.send().get(15, TimeUnit.SECONDS)
        }.onFailure {
            updateNotification("MQTT reconnecting")
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Alert transport")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Alert transport",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low-idle self-hosted MQTT transport status"
                setSound(null, null)
            }
        )
    }

    private fun stopTransport() {
        runCatching { client?.disconnect() }
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { client?.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.chanooh.alert.action.START_MQTT"
        const val ACTION_STOP = "dev.chanooh.alert.action.STOP_MQTT"
        private const val CHANNEL_ID = "transport_status"
        private const val NOTIFICATION_ID = 8001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MqttTransportService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MqttTransportService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
