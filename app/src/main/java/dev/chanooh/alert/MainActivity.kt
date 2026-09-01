package dev.chanooh.alert

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.chanooh.alert.alarm.CriticalAlarmService
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.settings.redacted
import dev.chanooh.alert.transport.MqttTransportService
import dev.chanooh.alert.ui.theme.AlertTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlertTheme { AlertHome() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertHome() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val secretStore = remember { SecretStore(context.applicationContext) }
    val persisted by repository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var mqttBroker by remember { mutableStateOf("") }
    var mqttUsername by remember { mutableStateOf("") }
    var mqttPassword by remember { mutableStateOf("") }
    var mqttEnabled by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf("") }
    var deviceApiToken by remember { mutableStateOf("") }
    var deviceHmacSecret by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(100f) }
    var restoreVolume by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(persisted) {
        if (!loaded) {
            serverUrl = persisted.serverBaseUrl
            mqttBroker = persisted.mqttBroker
            mqttUsername = persisted.mqttUsername
            mqttEnabled = persisted.mqttEnabled
            deviceId = persisted.deviceId
            mqttPassword = secretStore.getMqttPassword()
            deviceApiToken = secretStore.getDeviceApiToken()
            deviceHmacSecret = secretStore.getDeviceHmacSecret()
            volume = persisted.criticalVolumePercent.toFloat()
            restoreVolume = persisted.restoreVolumeAfterAck
            loaded = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(topBar = { TopAppBar(title = { Text("Alert") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(
                serverConfigured = persisted.serverBaseUrl.isNotBlank(),
                mqttConfigured = persisted.mqttBroker.isNotBlank() && persisted.deviceId.isNotBlank(),
                dndAccess = context.getSystemService(NotificationManager::class.java)
                    .isNotificationPolicyAccessGranted,
                notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Endpoints are entered only on this device. Secrets are encrypted with Android Keystore and are never committed to Git.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        serverUrl, { serverUrl = it }, Modifier.fillMaxWidth(),
                        label = { Text("Server base URL") }, placeholder = { Text("https://your-server.example") }, singleLine = true
                    )
                    OutlinedTextField(
                        mqttBroker, { mqttBroker = it }, Modifier.fillMaxWidth(),
                        label = { Text("MQTT broker") }, placeholder = { Text("mqtts://broker.example:8883") }, singleLine = true
                    )
                    OutlinedTextField(
                        mqttUsername, { mqttUsername = it }, Modifier.fillMaxWidth(),
                        label = { Text("MQTT username (optional)") }, singleLine = true
                    )
                    SecretField("MQTT password (optional)", mqttPassword) { mqttPassword = it }
                    SecretField("Device ID", deviceId) { deviceId = it }
                    SecretField("Device API token", deviceApiToken) { deviceApiToken = it }
                    SecretField("Device HMAC secret", deviceHmacSecret) { deviceHmacSecret = it }
                    Text("Saved device: ${persisted.deviceId.redacted()}", style = MaterialTheme.typography.bodySmall)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Self-hosted MQTT transport")
                            Text("300s keepalive · QoS 1 · signed events", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = mqttEnabled, onCheckedChange = { mqttEnabled = it })
                    }

                    HorizontalDivider()
                    Text("Critical volume: ${volume.toInt()}%")
                    Slider(value = volume, onValueChange = { volume = it }, valueRange = 10f..100f)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Restore alarm volume after ACK", modifier = Modifier.weight(1f))
                        Switch(checked = restoreVolume, onCheckedChange = { restoreVolume = it })
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                secretStore.setMqttPassword(mqttPassword)
                                secretStore.setDeviceApiToken(deviceApiToken)
                                secretStore.setDeviceHmacSecret(deviceHmacSecret)
                                repository.save(
                                    AppSettings(
                                        serverBaseUrl = serverUrl,
                                        mqttBroker = mqttBroker,
                                        mqttUsername = mqttUsername,
                                        mqttEnabled = mqttEnabled,
                                        deviceId = deviceId,
                                        criticalVolumePercent = volume.toInt(),
                                        restoreVolumeAfterAck = restoreVolume
                                    )
                                )
                                if (mqttEnabled) MqttTransportService.start(context) else MqttTransportService.stop(context)
                            }
                        }
                    ) { Text("Save & apply") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Reliability permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Notification permission") }
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Do Not Disturb access") }
                    if (Build.VERSION.SDK_INT >= 34) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Full-screen alert access") }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    CriticalAlarmService.start(
                        context,
                        "Critical test",
                        "Lock the screen, enable DND, and mute normal notifications to verify the alarm path.",
                        persisted.criticalVolumePercent
                    )
                }
            ) { Text("TEST CRITICAL ALERT") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SecretField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
}

@Composable
private fun StatusCard(
    serverConfigured: Boolean,
    mqttConfigured: Boolean,
    dndAccess: Boolean,
    notificationsAllowed: Boolean
) {
    val armed = serverConfigured && mqttConfigured && dndAccess && notificationsAllowed
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("System status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("Server", serverConfigured)
            StatusLine("MQTT", mqttConfigured)
            StatusLine("Notifications", notificationsAllowed)
            StatusLine("DND policy access", dndAccess)
            Text(
                if (armed) "ARMED" else "SETUP REQUIRED",
                color = if (armed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(if (ok) "OK" else "Needs attention", fontWeight = FontWeight.Medium)
    }
}
