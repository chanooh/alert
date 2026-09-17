package dev.chanooh.alert.transport

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import dev.chanooh.alert.alert.AlertDispatcher
import dev.chanooh.alert.alert.AlertEvent
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.settings.TransportMode
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MqttTransportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: Mqtt5AsyncClient? = null
    private var connectJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var transportReady = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        observeNetworkAvailability()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTransport()
            }
            else -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(if (transportReady) "MQTT 已连接" else "正在连接 MQTT…")
                )
                // Guardian and boot may both issue START. A healthy subscription must
                // be reused instead of deliberately disconnecting it every time.
                if (!transportReady && connectJob?.isActive != true) {
                    connectJob = scope.launch { connectSafely() }
                }
            }
        }
        return START_STICKY
    }

    private suspend fun connectSafely() {
        while (currentCoroutineContext().isActive) {
            val connected = runCatching { connect() }
            if (connected.isSuccess) return
            // A bad mobile network transition must not turn into a permanently
            // dead fallback transport.
            updateNotification("MQTT 连接失败，30 秒后重试")
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("告警通道")
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
                "告警通道",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "低功耗自建 MQTT 通道状态"
                setSound(null, null)
            }
        )
    }

    private suspend fun connect() {
        val settings = SettingsRepository(applicationContext).settings.first()
        if (!settings.mqttEnabled || settings.transportMode != TransportMode.APP_FALLBACK ||
            settings.mqttBroker.isBlank() || settings.deviceId.isBlank()) {
            stopTransport()
            return
        }

        val uri = URI(settings.mqttBroker)
        val secure = uri.scheme.equals("mqtts", ignoreCase = true)
        require(uri.scheme.equals("mqtt", true) || secure) { "MQTT URI must use mqtt:// or mqtts://" }
        val host = requireNotNull(uri.host) { "MQTT host is missing" }
        val port = if (uri.port > 0) uri.port else if (secure) 8883 else 1883
        val topic = "alert/${settings.deviceId}/events"

        transportReady = false
        runCatching { client?.disconnect()?.get(3, TimeUnit.SECONDS) }
        client = null

        lateinit var mqtt: Mqtt5AsyncClient
        val builder = MqttClient.builder()
            .identifier("alert-${settings.deviceId}")
            .serverHost(host)
            .serverPort(port)
            .addConnectedListener {
                updateNotification("MQTT 已连接，正在订阅…")
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
                        if (error != null) {
                            transportReady = false
                            updateNotification("MQTT 已连接，订阅失败，正在重试")
                            scheduleReconnect()
                        } else {
                            transportReady = true
                            updateNotification("MQTT 已连接")
                        }
                    }
            }
            .addDisconnectedListener {
                transportReady = false
                updateNotification("MQTT reconnecting")
                scheduleReconnect()
            }

        if (secure) builder.sslWithDefaultConfig()
        mqtt = builder.useMqttVersion5().buildAsync()
        client = mqtt

        val connectBuilder = mqtt.connectWith()
            .cleanStart(false)
            .sessionExpiryInterval(86_400)
            .keepAlive(300)

        if (settings.mqttUsername.isNotBlank()) {
            connectBuilder.simpleAuth()
                .username(settings.mqttUsername)
                .password(SecretStore(applicationContext).getMqttPassword().toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }

        connectBuilder.send().get(15, TimeUnit.SECONDS)
    }

    private fun stopTransport() {
        connectJob?.cancel()
        connectJob = null
        transportReady = false
        runCatching { client?.disconnect() }
        client = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connectJob?.cancel()
        connectJob = null
        transportReady = false
        runCatching { client?.disconnect() }
        client = null
        networkCallback?.let { callback -> runCatching { connectivityManager?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.chanooh.alert.action.START_MQTT"
        const val ACTION_STOP = "dev.chanooh.alert.action.STOP_MQTT"
        private const val CHANNEL_ID = "transport_status"
        private const val NOTIFICATION_ID = 8001
        private const val RECONNECT_DELAY_MS = 30_000L

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

    private fun scheduleReconnect(delayMillis: Long = RECONNECT_DELAY_MS) {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch {
            delay(delayMillis)
            connectSafely()
        }
    }

    private fun observeNetworkAvailability() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!transportReady) scheduleReconnect(delayMillis = 0)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !transportReady) {
                    scheduleReconnect(delayMillis = 0)
                }
            }
        }
        networkCallback = callback
        runCatching {
            connectivityManager?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
        }
    }
}
