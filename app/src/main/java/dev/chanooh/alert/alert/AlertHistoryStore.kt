package dev.chanooh.alert.alert

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class AlertHistoryItem(
    val id: String,
    val level: AlertLevel,
    val title: String,
    val message: String,
    val createdAt: Long,
    val acknowledged: Boolean
)

/**
 * Small durable, device-local inbox for the control-center home tab.
 * It intentionally never uploads or logs event content.
 */
object AlertHistoryStore {
    private const val PREFS = "alert_history"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 100
    private val lock = Any()
    private val state = MutableStateFlow<List<AlertHistoryItem>>(emptyList())
    private var preferences: android.content.SharedPreferences? = null

    val history: StateFlow<List<AlertHistoryItem>> = state.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (preferences == null) {
                preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                state.value = read(preferences!!)
            }
        }
    }

    fun record(context: Context, event: AlertEvent) {
        init(context)
        synchronized(lock) {
            val current = read(preferences!!)
            val item = AlertHistoryItem(
                id = event.id,
                level = event.level,
                title = event.title,
                message = event.message,
                createdAt = event.createdAt,
                acknowledged = false
            )
            val updated = (current.filterNot { it.id == event.id } + item)
                .sortedByDescending { it.createdAt }
                .take(MAX_ITEMS)
            write(updated)
        }
    }

    fun markAcknowledged(context: Context, ids: Iterable<String>) {
        init(context)
        val idSet = ids.toSet()
        if (idSet.isEmpty()) return
        synchronized(lock) {
            val updated = read(preferences!!).map { item ->
                if (item.id in idSet) item.copy(acknowledged = true) else item
            }
            write(updated)
        }
    }

    private fun read(prefs: android.content.SharedPreferences): List<AlertHistoryItem> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        AlertHistoryItem(
                            id = item.getString("id"),
                            level = AlertLevel.valueOf(item.getString("level")),
                            title = item.getString("title"),
                            message = item.getString("message"),
                            createdAt = item.getLong("createdAt"),
                            acknowledged = item.optBoolean("acknowledged", false)
                        )
                    )
                }
            }.sortedByDescending { it.createdAt }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<AlertHistoryItem>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("level", item.level.name)
                    .put("title", item.title)
                    .put("message", item.message)
                    .put("createdAt", item.createdAt)
                    .put("acknowledged", item.acknowledged)
            )
        }
        check(preferences!!.edit().putString(KEY_ITEMS, json.toString()).commit()) {
            "Unable to persist alert history"
        }
        state.value = items
    }
}
