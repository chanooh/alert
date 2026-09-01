package dev.chanooh.alert.system

import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Best-effort KernelSU/root override for Android's TOTAL SILENCE DND mode.
 *
 * NotificationChannel bypass cannot defeat INTERRUPTION_FILTER_NONE. For a
 * private rooted device we can temporarily disable manual DND using Android's
 * notification shell command and restore total-silence after the alert ends.
 * Other DND modes are left untouched so normal alarm/priority semantics apply.
 */
object RootDndController {
    enum class RestoreMode { TOTAL_SILENCE }

    fun overrideTotalSilenceIfNeeded(context: Context): RestoreMode? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) {
            return null
        }
        return if (runRootCommand("cmd notification set_dnd off")) {
            RestoreMode.TOTAL_SILENCE
        } else {
            null
        }
    }

    fun restore(mode: RestoreMode?) {
        when (mode) {
            RestoreMode.TOTAL_SILENCE -> runRootCommand("cmd notification set_dnd none")
            null -> Unit
        }
    }

    fun hasRoot(): Boolean = runRootCommand("id")

    private fun runRootCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private const val COMMAND_TIMEOUT_SECONDS = 2L
}
