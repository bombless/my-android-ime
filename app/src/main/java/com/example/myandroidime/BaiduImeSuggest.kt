package com.example.myandroidime

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BaiduImeSuggest {
    companion object {
        private const val TAG = "MyAndroidIME"
        private const val ENDPOINT = "https://www.baidu.com/sugrec?pre=1&p=3&ie=utf-8&json=1&prod=pc&from=pc_web&wd="
    }
    private val executor = Executors.newCachedThreadPool()
    private val cache = ConcurrentHashMap<String, List<String>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun candidates(context: String, pinyin: String): List<String> =
        cache[cacheKey(context, pinyin)].orEmpty()

    fun requestIfNeeded(context: String, pinyin: String, onUpdated: () -> Unit = {}) {
        val normalizedContext = context.trim()
        val normalizedPinyin = pinyin.trim().lowercase(Locale.ROOT)
        val key = cacheKey(normalizedContext, normalizedPinyin)
        if ((normalizedPinyin.length < 2 && normalizedContext.isEmpty()) || !inFlight.add(key)) return
        executor.execute {
            val requestStart = System.nanoTime()
            try {
                val searchPrefix = normalizedContext.takeLast(80)
                fetch(if (searchPrefix.isNotEmpty()) searchPrefix else normalizedPinyin)?.let { raw ->
                    val result = if (searchPrefix.isNotEmpty()) {
                        raw.mapNotNull { suggestion -> stripPrefixIgnoringPunctuation(suggestion, searchPrefix).takeIf { it.isNotBlank() } }.distinct().take(8)
                    } else raw
                    cache[key] = result
                    android.os.Handler(android.os.Looper.getMainLooper()).post(onUpdated)
                    ImeTelemetry.record("baidu_request", System.nanoTime() - requestStart, result.size)
                    Log.d(TAG, "Baidu suggestions updated context=${normalizedContext.takeLast(40)} pinyin=$normalizedPinyin count=${result.size} top=${result.take(5)}")
                }
            } catch (e: Exception) {
                ImeTelemetry.record("baidu_request", System.nanoTime() - requestStart, normalizedPinyin.length, "error")
                Log.w(TAG, "Baidu suggestion request failed context=${normalizedContext.takeLast(40)} pinyin=$normalizedPinyin", e)
            } finally { inFlight.remove(key) }
        }
    }

    private fun cacheKey(context: String, pinyin: String): String =
        context.trim() + "\u0000" + pinyin.trim().lowercase(Locale.ROOT)

    /**
     * Baidu may return a suggestion with punctuation normalized differently from
     * the text already in the editor, e.g. "你好，世界" vs "你好世界".
     * Strip the already-typed prefix by comparing meaningful characters while
     * tolerating punctuation/whitespace differences.
     */
    private fun stripPrefixIgnoringPunctuation(suggestion: String, prefix: String): String {
        var suggestionIndex = 0
        var prefixIndex = 0

        while (prefixIndex < prefix.length) {
            while (prefixIndex < prefix.length && isIgnorablePrefixChar(prefix[prefixIndex])) prefixIndex++
            if (prefixIndex >= prefix.length) break

            while (suggestionIndex < suggestion.length && isIgnorablePrefixChar(suggestion[suggestionIndex])) suggestionIndex++
            if (suggestionIndex >= suggestion.length || suggestion[suggestionIndex] != prefix[prefixIndex]) {
                return suggestion
            }

            suggestionIndex++
            prefixIndex++
        }

        // If the typed prefix itself ended in punctuation, consume the
        // candidate's equivalent punctuation too when it is present.
        if (prefix.isNotEmpty() && isIgnorablePrefixChar(prefix.last())) {
            while (suggestionIndex < suggestion.length && isIgnorablePrefixChar(suggestion[suggestionIndex])) {
                suggestionIndex++
            }
        }
        return suggestion.substring(suggestionIndex)
    }

    private fun isIgnorablePrefixChar(char: Char): Boolean {
        if (char.isWhitespace()) return true
        return when (Character.getType(char).toInt()) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt() -> true
            else -> false
        }
    }

    private fun fetch(pinyin: String): List<String>? {
        val encoded = URLEncoder.encode(pinyin, Charsets.UTF_8.name())
        val connection = (URL(ENDPOINT + encoded).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 4000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/153 Safari/537.36")
            setRequestProperty("Accept", "text/javascript, application/javascript, */*; q=0.01")
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("Referer", "https://www.baidu.com/s?wd=" + encoded)
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) return null
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(raw)
        } finally { connection.disconnect() }
    }

    private fun parse(raw: String): List<String> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()
        val groups = JSONObject(raw.substring(start, end + 1)).optJSONArray("g") ?: return emptyList()
        return buildList {
            for (i in 0 until groups.length()) {
                val text = groups.optJSONObject(i)?.optString("q").orEmpty().trim()
                if (text.isNotEmpty() && text.length <= 24) add(text)
            }
        }.distinct().take(8)
    }
}