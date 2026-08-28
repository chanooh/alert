package dev.chanooh.alert.alert

import android.content.Context

class EventDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)

    @Synchronized
    fun tryReserve(eventId: String): Boolean {
        val existing = readIds().toMutableList()
        if (eventId in existing) return false
        existing.add(0, eventId)
        writeIds(existing)
        return true
    }

    @Synchronized
    fun forget(eventId: String) {
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
            .apply()
    }

    private companion object {
        const val KEY_IDS = "recent_event_ids"
        const val MAX_IDS = 256
    }
}
