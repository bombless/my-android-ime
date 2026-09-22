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

    fun candidates(pinyin: String): List<String> = cache[pinyin.trim().lowercase(Locale.ROOT)].orEmpty()

    fun requestIfNeeded(pinyin: String, onUpdated: () -> Unit = {}) {
        val key = pinyin.trim().lowercase(Locale.ROOT)
        if (key.length < 2 || !inFlight.add(key)) return
        executor.execute {
            val requestStart = System.nanoTime()
            try {
                fetch(key)?.let { result ->
                    cache[key] = result
                    android.os.Handler(android.os.Looper.getMainLooper()).post(onUpdated)
                    ImeTelemetry.record("baidu_request", System.nanoTime() - requestStart, result.size)
                    Log.d(TAG, "Baidu suggestions updated pinyin=$key count=${result.size} top=${result.take(5)}")
                }
            } catch (e: Exception) {
                ImeTelemetry.record("baidu_request", System.nanoTime() - requestStart, key.length, "ok")
                Log.w(TAG, "Baidu suggestion request failed pinyin=$" + "key", e)
            } finally { inFlight.remove(key) }
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