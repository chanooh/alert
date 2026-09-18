package dev.chanooh.alert.alert

import android.content.Context

class ActiveAlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_alert", Context.MODE_PRIVATE)

    fun add(eventId: String) = synchronized(LOCK) {
        if (eventId.isBlank()) return@synchronized
        val ids = readIds().toMutableList()
        if (eventId !in ids) ids.add(eventId)
        writeIds(ids)
    }

    fun contains(eventId: String): Boolean = synchronized(LOCK) {
        eventId in readIds()
    }

    fun remove(eventId: String) = synchronized(LOCK) {
        writeIds(readIds().filterNot { it == eventId })
    }

    fun drain(): List<String> = synchronized(LOCK) {
        val ids = readIds()
        prefs.edit().remove(KEY_EVENT_IDS).commit()
        ids
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
        val LOCK = Any()
        const val KEY_EVENT_IDS = "event_ids"
    }
}
