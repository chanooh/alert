package dev.chanooh.alert.system

import android.app.NotificationManager
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Best-effort KernelSU/root override for active Android DND modes.
 *
 * Notification-channel bypass is not a hard guarantee for every DND policy.
 * On this private rooted-device path we temporarily disable an active DND
 * filter before a CRITICAL alarm and restore the previous filter level when
 * the alert ends. We intentionally do nothing when DND is already off.
 */
object RootDndController {
    enum class RestoreMode(val shellValue: String) {
        PRIORITY("priority"),
        TOTAL_SILENCE("none"),
        ALARMS_ONLY("alarms")
    }

    fun overrideIfNeeded(context: Context): RestoreMode? {
        val manager = context.getSystemService(NotificationManager::class.java)
        val restoreMode = when (manager.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> RestoreMode.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_NONE -> RestoreMode.TOTAL_SILENCE
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> RestoreMode.ALARMS_ONLY
            else -> null
        } ?: return null

        val disabled = runRootCommand("cmd notification set_dnd off") ||
            runRootCommand("cmd notification set_dnd all")
        return if (disabled) restoreMode else null
    }

    fun restore(mode: RestoreMode?) {
        if (mode != null) runRootCommand("cmd notification set_dnd ${mode.shellValue}")
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
