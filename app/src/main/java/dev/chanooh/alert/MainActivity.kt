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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.chanooh.alert.alarm.CriticalAlarmService
import dev.chanooh.alert.alert.AlertHistoryItem
import dev.chanooh.alert.alert.AlertHistoryStore
import dev.chanooh.alert.alert.AlertLevel
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.SettingsRepository
import dev.chanooh.alert.settings.redacted
import dev.chanooh.alert.transport.MqttTransportService
import dev.chanooh.alert.ui.theme.AlertTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertHistoryStore.init(applicationContext)
        setContent { AlertTheme { AlertHome() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertHome() {
    val context = LocalContext.current
    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val repository = remember { SettingsRepository(context.applicationContext) }
    val secretStore = remember { SecretStore(context.applicationContext) }
    val persisted by repository.settings.collectAsState(initial = AppSettings())
    val history by AlertHistoryStore.history.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(0) }

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
    var rootDndOverride by remember { mutableStateOf(false) }
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
            rootDndOverride = persisted.rootDndOverrideEnabled
            loaded = true
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || notificationManager.canUseFullScreenIntent()

    Scaffold(topBar = { TopAppBar(title = { Text("告警") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("通知") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("设置") }
                )
            }

            if (selectedTab == 0) {
                NotificationTab(history, Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    StatusCard(
                        serverConfigured = persisted.serverBaseUrl.isNotBlank(),
                        mqttConfigured = persisted.mqttBroker.isNotBlank() && persisted.deviceId.isNotBlank(),
                        dndAccess = notificationManager.isNotificationPolicyAccessGranted,
                        notificationsAllowed = notificationsAllowed,
                        fullScreenAllowed = fullScreenAllowed
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("连接设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "地址只在本机填写。密钥由 Android Keystore 加密保存，永远不会提交到 Git。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        serverUrl, { serverUrl = it }, Modifier.fillMaxWidth(),
                        label = { Text("服务器地址") }, placeholder = { Text("https://your-server.example") }, singleLine = true
                    )
                    OutlinedTextField(
                        mqttBroker, { mqttBroker = it }, Modifier.fillMaxWidth(),
                        label = { Text("MQTT 代理地址") }, placeholder = { Text("mqtts://broker.example:8883") }, singleLine = true
                    )
                    OutlinedTextField(
                        mqttUsername, { mqttUsername = it }, Modifier.fillMaxWidth(),
                        label = { Text("MQTT 用户名（可选）") }, singleLine = true
                    )
                    SecretField("MQTT 密码（可选）", mqttPassword) { mqttPassword = it }
                    SecretField("设备 ID", deviceId) { deviceId = it }
                    SecretField("设备 API Token", deviceApiToken) { deviceApiToken = it }
                    SecretField("设备 HMAC 密钥", deviceHmacSecret) { deviceHmacSecret = it }
                    Text("已保存设备：${persisted.deviceId.redacted()}", style = MaterialTheme.typography.bodySmall)

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("自建 MQTT 通道")
                            Text("300 秒保活 · QoS 1 · 事件签名", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = mqttEnabled, onCheckedChange = { mqttEnabled = it })
                    }

                    HorizontalDivider()
                    Text("Critical 音量：${volume.toInt()}%")
                    Slider(value = volume, onValueChange = { volume = it }, valueRange = 10f..100f)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("ACK 后恢复闹钟音量", modifier = Modifier.weight(1f))
                        Switch(checked = restoreVolume, onCheckedChange = { restoreVolume = it })
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Root 免打扰覆盖")
                            Text(
                                "可选的 KernelSU 路径。Critical 告警期间会临时关闭免打扰，并在 ACK 后恢复原有级别。请先在 KernelSU 中授予告警应用 Root 权限。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(checked = rootDndOverride, onCheckedChange = { rootDndOverride = it })
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
                                        restoreVolumeAfterAck = restoreVolume,
                                        rootDndOverrideEnabled = rootDndOverride
                                    )
                                )
                                if (mqttEnabled) MqttTransportService.start(context) else MqttTransportService.stop(context)
                            }
                        }
                    ) { Text("保存并应用") }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("可靠性权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("通知权限") }
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("免打扰访问权限") }
                    if (Build.VERSION.SDK_INT >= 34) {
                        Button(
                            onClick = {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("全屏提醒权限") }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    CriticalAlarmService.start(
                        context = context,
                        title = "Critical 测试",
                        message = "锁定屏幕、开启免打扰并静音普通通知，以验证告警路径。",
                        volumePercent = persisted.criticalVolumePercent,
                        restoreVolume = persisted.restoreVolumeAfterAck,
                        rootDndOverride = persisted.rootDndOverrideEnabled
                    )
                }
            ) { Text("测试 Critical 告警") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
}
}

@Composable
private fun NotificationTab(history: List<AlertHistoryItem>, modifier: Modifier = Modifier) {
    if (history.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("暂无通知", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("收到告警后，详细内容会显示在这里。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("最近通知", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        history.forEach { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = levelLabel(item.level),
                            color = if (item.level == AlertLevel.CRITICAL) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (item.acknowledged) "已确认" else "等待确认",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(text = item.message, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = formatTimestamp(item.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun levelLabel(level: AlertLevel): String = when (level) {
    AlertLevel.INFO -> "Info 通知"
    AlertLevel.WARNING -> "Warning 告警"
    AlertLevel.URGENT -> "Urgent 告警"
    AlertLevel.CRITICAL -> "Critical 告警"
}

private fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

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
    notificationsAllowed: Boolean,
    fullScreenAllowed: Boolean
) {
    val armed = serverConfigured && mqttConfigured && dndAccess && notificationsAllowed && fullScreenAllowed
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("系统状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusLine("服务器", serverConfigured)
            StatusLine("MQTT", mqttConfigured)
            StatusLine("通知", notificationsAllowed)
            StatusLine("免打扰权限", dndAccess)
            StatusLine("全屏提醒", fullScreenAllowed)
            Text(
                if (armed) "已就绪" else "需要设置",
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
        Text(if (ok) "正常" else "需要处理", fontWeight = FontWeight.Medium)
    }
}
