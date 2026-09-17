package dev.chanooh.alert.transport

import android.content.Context
import dev.chanooh.alert.security.SecretStore
import dev.chanooh.alert.settings.AppSettings
import dev.chanooh.alert.settings.TransportMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * The module runs as root, but the configuration and inbox stay under the
 * app's private directory so no credential is ever written to /data/adb.
 * HMAC and HTTP bearer secrets deliberately never cross this boundary.
 */
object RootTransportFiles {
    private const val DIRECTORY = "root_transport"
    private const val CONFIG_FILE = "config.json"
    private const val STATUS_FILE = "status.json"
    private const val INBOX_DIRECTORY = "inbox"
    private const val SCHEMA = 1

    data class Status(
        val state: String = "未同步",
        val generation: Long = 0,
        val lastConnectedAt: Long = 0,
        val lastEventAt: Long = 0,
        val pendingInbox: Int = 0,
        val lastError: String = ""
    ) {
        fun display(): String = when (state) {
            "subscribed" -> "Root MQTT 已订阅"
            "connecting" -> "Root MQTT 正在连接"
            "backoff" -> if (lastError.isBlank()) "Root MQTT 正在重连" else "Root MQTT 重连：$lastError"
            "disabled" -> "Root MQTT 已关闭"
            else -> "Root 模块未同步或未启动"
        }
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)
    private fun config(context: Context) = File(directory(context), CONFIG_FILE)
    private fun statusFile(context: Context) = File(directory(context), STATUS_FILE)
    fun inbox(context: Context) = File(directory(context), INBOX_DIRECTORY)

    fun sync(context: Context, settings: AppSettings, secrets: SecretStore) {
        val target = config(context)
        target.parentFile?.mkdirs()
        inbox(context).mkdirs()
        val generation = System.currentTimeMillis()
        atomicWrite(target, rootConfigJson(settings, secrets.getMqttPassword(), generation).toByteArray(Charsets.UTF_8))
    }

    internal fun rootConfigJson(settings: AppSettings, mqttPassword: String, generation: Long): String {
        val enabled = settings.mqttEnabled && settings.transportMode == TransportMode.KERNELSU_ROOT &&
            settings.mqttBroker.isNotBlank() && settings.deviceId.isNotBlank()
        // Keep this serializer JVM-testable: Android's platform JSONObject is
        // a throwing stub in local unit tests. Every dynamic field is escaped.
        return """{"schema":$SCHEMA,"enabled":$enabled,"generation":$generation,"broker":${quote(if (enabled) settings.mqttBroker.trim() else "")},"username":${quote(if (enabled) settings.mqttUsername.trim() else "")},"password":${quote(if (enabled) mqttPassword else "")},"deviceId":${quote(if (enabled) settings.deviceId.trim() else "")}}"""
    }

    fun status(context: Context): Status {
        val json = runCatching { JSONObject(statusFile(context).readText(Charsets.UTF_8)) }.getOrNull() ?: return Status()
        return Status(
            state = json.optString("state", "unknown"),
            generation = json.optLong("generation", 0),
            lastConnectedAt = json.optLong("lastConnectedAt", 0),
            lastEventAt = json.optLong("lastEventAt", 0),
            pendingInbox = json.optInt("pendingInbox", 0).coerceAtLeast(0),
            lastError = json.optString("lastError", "").take(120)
        )
    }

    fun pendingEvents(context: Context): List<File> = inbox(context)
        .listFiles { file -> file.isFile && file.name.endsWith(".json") }
        ?.sortedBy { it.name }
        .orEmpty()

    fun deleteInboxEntry(file: File) {
        runCatching { file.delete() }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.outputStream().use { it.write(bytes) }
        runCatching {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            temporary.renameTo(target)
        }
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
