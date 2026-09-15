package dev.chanooh.alert.alert

import android.content.Context

class EventDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)

    fun tryReserve(eventId: String): Boolean = synchronized(LOCK) {
        val existing = readIds().toMutableList()
        if (eventId in existing) return@synchronized false
        existing.add(0, eventId)
        writeIds(existing)
        true
    }

    fun forget(eventId: String) = synchronized(LOCK) {
        writeIds(readIds().filterNot { it == eventId })
    }

    private fun readIds(): List<String> = prefs.getString(KEY_IDS, "")
        .orEmpty()
        .lineSequence()
        .filter { it.isNotBlank() }
        .toList()

    private fun writeIds(ids: List<String>) {
        prefs.edit()
            .putString(KEY_IDS, ids.distinct().take(MAX_IDS).joinToString("\n"))
            .commit()
    }

    private companion object {
        val LOCK = Any()
        const val KEY_IDS = "recent_event_ids"
        const val MAX_IDS = 256
    }
}
