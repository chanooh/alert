package dev.chanooh.alert.alert

import android.content.Context

class ActiveAlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_alert", Context.MODE_PRIVATE)

    @Synchronized
    fun add(eventId: String) {
        if (eventId.isBlank()) return
        val ids = readIds().toMutableList()
        if (eventId !in ids) ids.add(eventId)
        writeIds(ids)
    }

    @Synchronized
    fun remove(eventId: String) {
        writeIds(readIds().filterNot { it == eventId })
    }

    @Synchronized
    fun drain(): List<String> {
        val ids = readIds()
        prefs.edit().remove(KEY_EVENT_IDS).commit()
        return ids
    }

    private fun readIds(): List<String> = prefs.getString(KEY_EVENT_IDS, "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

    private fun writeIds(ids: List<String>) {
        prefs.edit().putString(KEY_EVENT_IDS, ids.distinct().joinToString("\n")).commit()
    }

    private companion object {
        const val KEY_EVENT_IDS = "event_ids"
    }
}
