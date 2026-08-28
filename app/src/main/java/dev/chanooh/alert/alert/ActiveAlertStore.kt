package dev.chanooh.alert.alert

import android.content.Context

class ActiveAlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("active_alert", Context.MODE_PRIVATE)

    fun set(eventId: String) {
        prefs.edit().putString(KEY_EVENT_ID, eventId).apply()
    }

    fun get(): String = prefs.getString(KEY_EVENT_ID, "").orEmpty()

    fun clear() {
        prefs.edit().remove(KEY_EVENT_ID).apply()
    }

    private companion object {
        const val KEY_EVENT_ID = "event_id"
    }
}
