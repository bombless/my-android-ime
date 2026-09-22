package com.example.myandroidime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class HistoryCandidate(
    val query: String,
    val text: String,
    val count: Int,
    val lastSelectedAt: Long,
)

/** Persists selected candidate text and its count for each exact composing input. */
class InputHistoryStore(context: Context) {
    private companion object {
        const val PREFS = "ime_input_history"
        const val KEY_HISTORY = "candidates"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordSelection(query: String, text: String) {
        val normalizedQuery = query.trim()
        val normalizedText = text.trim()
        if (normalizedQuery.isEmpty() || normalizedText.isEmpty()) return

        val history = loadMutable()
        val queryKey = normalizedQuery.lowercase(Locale.ROOT)
        val textKey = normalizedText.lowercase(Locale.ROOT)
        val key = "$queryKey\\u0000$textKey"
        val old = history[key]
        history[key] = HistoryCandidate(
            query = old?.query ?: normalizedQuery,
            text = old?.text ?: normalizedText,
            count = (old?.count ?: 0) + 1,
            lastSelectedAt = System.currentTimeMillis(),
        )
        save(history)
    }

    @Synchronized
    fun candidates(query: String, limit: Int = 8): List<HistoryCandidate> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()
        val queryKey = normalizedQuery.lowercase(Locale.ROOT)
        return loadMutable().values
            .filter { it.query.lowercase(Locale.ROOT) == queryKey }
            .sortedWith(
                compareByDescending<HistoryCandidate> { it.count }
                    .thenByDescending { it.lastSelectedAt }
            )
            .take(limit)
    }

    private fun loadMutable(): MutableMap<String, HistoryCandidate> {
        val result = mutableMapOf<String, HistoryCandidate>()
        val raw = prefs.getString(KEY_HISTORY, null) ?: return result
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val query = item.optString("query").trim()
                val text = item.optString("text").trim()
                val count = item.optInt("count", 0)
                if (query.isNotEmpty() && text.isNotEmpty() && count > 0) {
                    val key = query.lowercase(Locale.ROOT) + "\\u0000" + text.lowercase(Locale.ROOT)
                    result[key] = HistoryCandidate(
                        query = query,
                        text = text,
                        count = count,
                        lastSelectedAt = item.optLong("lastSelectedAt", 0L),
                    )
                }
            }
        }
        return result
    }

    private fun save(history: Map<String, HistoryCandidate>) {
        val array = JSONArray()
        history.values.forEach { item ->
            array.put(JSONObject().apply {
                put("query", item.query)
                put("text", item.text)
                put("count", item.count)
                put("lastSelectedAt", item.lastSelectedAt)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }
}
