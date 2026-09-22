package com.example.myandroidime

import android.content.Context
import com.example.ime.core.Candidate
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class UserDictionaryEntry(
    val pinyin: String,
    val word: String,
    val frequency: Int = 1,
) {
    val key: String get() = "$pinyin\t$word"
}

class UserDictionaryStorage(context: Context) {
    private val prefs = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<UserDictionaryEntry> {
        val raw = prefs.getString("entries", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(UserDictionaryEntry(
                        pinyin = o.optString("pinyin").trim(),
                        word = o.optString("word"),
                        frequency = o.optInt("frequency", 1).coerceAtLeast(1),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(entries: List<UserDictionaryEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject()
                .put("pinyin", it.pinyin)
                .put("word", it.word)
                .put("frequency", it.frequency.coerceAtLeast(1)))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }
}

class UserDictionaryRepository(context: Context) {
    private val storage = UserDictionaryStorage(context.applicationContext)

    @Synchronized
    fun search(query: String): List<UserDictionaryEntry> {
        val q = query.trim().lowercase(Locale.ROOT)
        return storage.load()
            .filter { q.isEmpty() || it.pinyin.lowercase(Locale.ROOT).contains(q) || it.word.contains(q) }
            .sortedWith(compareByDescending<UserDictionaryEntry> { it.frequency }.thenBy { it.pinyin }.thenBy { it.word })
    }

    @Synchronized
    fun add(entry: UserDictionaryEntry) {
        require(entry.pinyin.isNotBlank()) { "拼音不能为空" }
        require(entry.word.isNotBlank()) { "词不能为空" }
        val entries = storage.load().toMutableList()
        require(entries.none { it.key == entry.key }) { "相同的拼音和词已存在" }
        entries += entry.copy(pinyin = entry.pinyin.trim(), word = entry.word.trim(), frequency = entry.frequency.coerceAtLeast(1))
        storage.save(entries)
    }

    @Synchronized
    fun update(originalKey: String, entry: UserDictionaryEntry) {
        require(entry.pinyin.isNotBlank()) { "拼音不能为空" }
        require(entry.word.isNotBlank()) { "词不能为空" }
        val entries = storage.load().toMutableList()
        if (originalKey != entry.key && entries.any { it.key == entry.key }) {
            throw IllegalArgumentException("相同的拼音和词已存在")
        }
        val index = entries.indexOfFirst { it.key == originalKey }
        require(index >= 0) { "词库条目不存在" }
        entries[index] = entry.copy(pinyin = entry.pinyin.trim(), word = entry.word.trim(), frequency = entry.frequency.coerceAtLeast(1))
        storage.save(entries)
    }

    @Synchronized
    fun delete(key: String) {
        storage.save(storage.load().filterNot { it.key == key })
    }

    @Synchronized
    fun incrementFrequency(pinyin: String, word: String) {
        val key = "$pinyin\t$word"
        val entries = storage.load().map {
            if (it.key == key) it.copy(frequency = it.frequency + 1) else it
        }
        storage.save(entries)
    }

    fun candidates(pinyin: String, limit: Int = 8): List<Candidate> {
        val q = pinyin.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return emptyList()
        return storage.load()
            .filter { it.pinyin.replace(" ", "").lowercase(Locale.ROOT).startsWith(q) }
            .sortedWith(compareByDescending<UserDictionaryEntry> { it.frequency }.thenBy { it.pinyin }.thenBy { it.word })
            .take(limit)
            .map { Candidate(it.word, it.pinyin, it.frequency) }
    }
}
