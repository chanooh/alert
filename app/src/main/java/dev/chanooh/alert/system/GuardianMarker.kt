package dev.chanooh.alert.system

import android.content.Context
import java.io.File

object GuardianMarker {
    private const val FILE_NAME = "guardian_mqtt_enabled"
    private const val HEARTBEAT_FILE_NAME = "guardian_mqtt_heartbeat"

    fun setEnabled(context: Context, enabled: Boolean) {
        val marker = File(context.filesDir, FILE_NAME)
        if (enabled) {
            runCatching {
                marker.parentFile?.mkdirs()
                marker.writeText("1\n")
            }
        } else {
            runCatching { marker.delete() }
            runCatching { File(context.filesDir, HEARTBEAT_FILE_NAME).delete() }
        }
    }

    fun recordHealthyTransport(context: Context) {
        runCatching {
            File(context.filesDir, HEARTBEAT_FILE_NAME).apply {
                parentFile?.mkdirs()
                writeText("${System.currentTimeMillis()}\n")
            }
        }
    }
}
