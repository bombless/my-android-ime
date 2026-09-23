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

data class NextWordCandidate(
    val previousText: String,
    val text: String,
    val count: Int,
    val lastSelectedAt: Long,
)

/** Persists selected candidate text and its count for each exact composing input. */
class InputHistoryStore(context: Context) {
    private companion object {
        const val PREFS = "ime_input_history"
        const val KEY_HISTORY = "candidates"
        const val KEY_NEXT_WORD = "next_word_candidates"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun recordSelection(query: String, text: String, previousText: String? = null) {
        val normalizedQuery = query.trim()
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return

        val normalizedPrevious = previousText?.trim().orEmpty()
        if (normalizedPrevious.isNotEmpty() && !normalizedPrevious.equals(normalizedText, ignoreCase = true)) {
            recordNextWord(normalizedPrevious, normalizedText)
        }

        if (normalizedQuery.isEmpty()) return

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

    @Synchronized
    fun nextWordCandidates(previousText: String, limit: Int = 8): List<NextWordCandidate> {
        val normalizedPrevious = previousText.trim()
        if (normalizedPrevious.isEmpty()) return emptyList()
        val previousKey = normalizedPrevious.lowercase(Locale.ROOT)
        return loadNextWordMutable().values
            .filter { it.previousText.lowercase(Locale.ROOT) == previousKey }
            .sortedWith(
                compareByDescending<NextWordCandidate> { it.count }
                    .thenByDescending { it.lastSelectedAt }
            )
            .take(limit)
    }

    private fun recordNextWord(previousText: String, text: String) {
        val transitions = loadNextWordMutable()
        val key = previousText.lowercase(Locale.ROOT) + "\u0000" + text.lowercase(Locale.ROOT)
        val old = transitions[key]
        transitions[key] = NextWordCandidate(
            previousText = old?.previousText ?: previousText,
            text = old?.text ?: text,
            count = (old?.count ?: 0) + 1,
            lastSelectedAt = System.currentTimeMillis(),
        )
        saveNextWord(transitions)
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

    private fun loadNextWordMutable(): MutableMap<String, NextWordCandidate> {
        val result = mutableMapOf<String, NextWordCandidate>()
        val raw = prefs.getString(KEY_NEXT_WORD, null) ?: return result
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val previousText = item.optString("previousText").trim()
                val text = item.optString("text").trim()
                val count = item.optInt("count", 0)
                if (previousText.isNotEmpty() && text.isNotEmpty() && count > 0) {
                    val key = previousText.lowercase(Locale.ROOT) + "\u0000" + text.lowercase(Locale.ROOT)
                    result[key] = NextWordCandidate(
                        previousText = previousText,
                        text = text,
                        count = count,
                        lastSelectedAt = item.optLong("lastSelectedAt", 0L),
                    )
                }
            }
        }
        return result
    }

    private fun saveNextWord(transitions: Map<String, NextWordCandidate>) {
        val array = JSONArray()
        transitions.values.forEach { item ->
            array.put(JSONObject().apply {
                put("previousText", item.previousText)
                put("text", item.text)
                put("count", item.count)
                put("lastSelectedAt", item.lastSelectedAt)
            })
        }
        prefs.edit().putString(KEY_NEXT_WORD, array.toString()).apply()
    }
}
