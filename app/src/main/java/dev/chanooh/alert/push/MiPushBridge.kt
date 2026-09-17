package dev.chanooh.alert.push

import android.content.Context
import android.content.Intent
import dev.chanooh.alert.BuildConfig
import dev.chanooh.alert.alert.AlertDispatcher
import dev.chanooh.alert.alert.AlertEvent
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.SettingsRepository
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Keeps the vendor SDK behind a tiny boundary. It is deliberately reflective so
 * normal source/CI builds remain possible until the official AAR is supplied.
 * When present, only the official SDK class is invoked; no push secret is held
 * by Android.
 */
object MiPushBridge {
    private const val PREFS = "mipush_status"
    private const val KEY_STATUS = "status"
    private const val KEY_REGISTRATION = "registration"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configured(): Boolean = BuildConfig.MIPUSH_APP_ID.isNotBlank() && BuildConfig.MIPUSH_APP_KEY.isNotBlank()

    fun status(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_STATUS, if (configured()) "等待注册" else "未配置") ?: "未配置"

    fun registerAndSync(context: Context) {
        if (!configured()) return
        val appContext = context.applicationContext
        runCatching {
            val client = Class.forName("com.xiaomi.mipush.sdk.MiPushClient")
            client.getMethod("registerPush", Context::class.java, String::class.java, String::class.java)
                .invoke(null, appContext, BuildConfig.MIPUSH_APP_ID, BuildConfig.MIPUSH_APP_KEY)
            client.getMethod("getRegId", Context::class.java).invoke(null, appContext) as? String
        }.onSuccess { registrationId ->
            if (!registrationId.isNullOrBlank()) submitRegistration(appContext, registrationId)
            else updateStatus(appContext, "正在向小米推送注册")
        }.onFailure {
            updateStatus(appContext, "Mi Push SDK 未就绪")
        }
    }

    fun handleIntent(context: Context, intent: Intent?) {
        extractEventId(intent)?.let { handlePayload(context, "{\"id\":\"$it\"}") }
    }

    fun handlePayload(context: Context, payload: String?) {
        val eventId = runCatching { JSONObject(payload.orEmpty()).optString("id") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        scope.launch { fetchAndDispatch(context.applicationContext, eventId) }
    }

    fun onRegistrationId(context: Context, registrationId: String) {
        if (registrationId.isNotBlank()) submitRegistration(context.applicationContext, registrationId)
    }

    private fun submitRegistration(context: Context, registrationId: String) {
        scope.launch {
            val settings = SettingsRepository(context).settings.first()
            val token = SecretStore(context).getDeviceApiToken()
            if (settings.serverBaseUrl.isBlank() || settings.deviceId.isBlank() || token.isBlank()) {
                updateStatus(context, "等待服务器与设备凭据")
                return@launch
            }
            val success = runCatching {
                val connection = (URL(settings.serverBaseUrl.trimEnd('/') + "/api/device/mipush-registration")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { output ->
                    output.write(JSONObject().put("deviceId", settings.deviceId).put("registrationId", registrationId).toString().toByteArray())
                }
                val code = connection.responseCode
                connection.disconnect()
                code in 200..299
            }.getOrDefault(false)
            if (success) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_REGISTRATION, registrationId).apply()
                updateStatus(context, "已注册并同步")
            } else updateStatus(context, "注册信息同步失败")
        }
    }

    private suspend fun fetchAndDispatch(context: Context, eventId: String) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository(context).settings.first()
        val token = SecretStore(context).getDeviceApiToken()
        if (settings.serverBaseUrl.isBlank() || settings.deviceId.isBlank() || token.isBlank()) return@withContext
        val event = runCatching {
            val connection = (URL(settings.serverBaseUrl.trimEnd('/') + "/api/device/alerts/$eventId")
                .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("X-Device-Id", settings.deviceId)
                }
            if (connection.responseCode !in 200..299) error("event fetch failed")
            val body = connection.inputStream.use { it.readBytes() }
            connection.disconnect()
            AlertEvent.fromJson(body)
        }.getOrNull() ?: return@withContext
        AlertDispatcher(context).handle(event)
    }

    private fun extractEventId(intent: Intent?): String? {
        val extras = intent?.extras ?: return null
        val candidates = listOf("payload", "message", "mipush_payload")
            .mapNotNull { extras.getString(it) }
        return candidates.firstNotNullOfOrNull { value ->
            runCatching { JSONObject(value).optString("id").takeIf { it.isNotBlank() } }.getOrNull()
        }
    }

    private fun updateStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, status).apply()
    }
}
