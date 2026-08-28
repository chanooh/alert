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
import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.settings.redacted
import dev.chanooh.alert.ui.theme.AlertTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AlertTheme {
                AlertHome()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertHome() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository(context.applicationContext) }
    val persisted by repository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf("") }
    var mqttBroker by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var volume by remember { mutableStateOf(100f) }
    var restoreVolume by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(persisted) {
        if (!loaded || persisted != AppSettings()) {
            serverUrl = persisted.serverBaseUrl
            mqttBroker = persisted.mqttBroker
            deviceId = persisted.deviceId
            volume = persisted.criticalVolumePercent.toFloat()
            restoreVolume = persisted.restoreVolumeAfterAck
            loaded = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Alert") }) }
    ) { padding ->
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
                dndAccess = context.getSystemService(NotificationManager::class.java)
                    .isNotificationPolicyAccessGranted,
                notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Values are entered on-device. No production endpoint or device credential is stored in the repository.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Server base URL") },
                        placeholder = { Text("https://your-server.example") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mqttBroker,
                        onValueChange = { mqttBroker = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("MQTT broker (optional)") },
                        placeholder = { Text("mqtts://broker.example:8883") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = deviceId,
                        onValueChange = { deviceId = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Device ID") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Text("Saved device: ${persisted.deviceId.redacted()}", style = MaterialTheme.typography.bodySmall)

                    HorizontalDivider()
                    Text("Critical volume: ${volume.toInt()}%")
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        valueRange = 10f..100f
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Restore previous alarm volume after ACK")
                        Switch(checked = restoreVolume, onCheckedChange = { restoreVolume = it })
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                repository.save(
                                    AppSettings(
                                        serverBaseUrl = serverUrl,
                                        mqttBroker = mqttBroker,
                                        deviceId = deviceId,
                                        criticalVolumePercent = volume.toInt(),
                                        restoreVolumeAfterAck = restoreVolume
                                    )
                                )
                            }
                        }
                    ) { Text("Save configuration") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Reliability permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                })
                            }
                        }
                    ) { Text("Notification permission") }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                    ) { Text("Do Not Disturb access") }
                    if (Build.VERSION.SDK_INT >= 34) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            }
                        ) { Text("Full-screen alert access") }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    CriticalAlarmService.start(
                        context = context,
                        title = "Critical test",
                        message = "If you can hear this with DND enabled and the screen locked, the critical path is working.",
                        volumePercent = persisted.criticalVolumePercent
                    )
                }
            ) {
                Text("TEST CRITICAL ALERT")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusCard(
    serverConfigured: Boolean,
    dndAccess: Boolean,
    notificationsAllowed: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("System status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("Server", serverConfigured)
            StatusLine("Notifications", notificationsAllowed)
            StatusLine("DND policy access", dndAccess)
            Text(
                if (serverConfigured && notificationsAllowed && dndAccess) "ARMED" else "SETUP REQUIRED",
                color = if (serverConfigured && notificationsAllowed && dndAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Text(if (ok) "OK" else "Needs attention", fontWeight = FontWeight.Medium)
    }
}
