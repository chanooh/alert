package dev.chanooh.alert.alert

import android.content.Context

class EventDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences("alert_dedupe", Context.MODE_PRIVATE)

    @Synchronized
    fun seenOrMark(eventId: String): Boolean {
        val existing = prefs.getString(KEY_IDS, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()

        if (eventId in existing) return true
        existing.add(0, eventId)
        val compact = existing.take(MAX_IDS).joinToString("\n")
        prefs.edit().putString(KEY_IDS, compact).apply()
        return false
    }

    private companion object {
        const val KEY_IDS = "recent_event_ids"
        const val MAX_IDS = 256
    }
}
