package com.example.myandroidime

import android.content.Context
import android.util.Log
import com.example.ime.core.Candidate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper

/** Persistent, best-effort AI candidate patches. Local Rime shards remain the fallback. */
class DeepSeekImeAi(context: Context) {
    companion object {
        private const val TAG = "MyAndroidIME"
        private const val PREFS = "deepseek_ime"
        private const val API_KEY = "api_key"
        private const val PATCH_FILE = "deepseek-ime-patches.json"
        private const val MODEL = "deepseek-flash"
        private const val ENDPOINT = "https://api.deepseek.com/beta/chat/completions"
        private val FEW_SHOT = listOf(
            "w->我,为,五,玩,哇,王,问",
            "q->请,去,其,前,却,钱,七",
            "shi->是,时,事,市,十,使,实",
            "wo->我,握,窝,卧,沃,喔",
            "zhong->中,种,重,众,钟,终",
            "guo->国,过,果,郭,锅,裹",
        )
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val patchFile = File(context.applicationContext.filesDir, PATCH_FILE)
    private val executor = Executors.newCachedThreadPool()
    private val patches = ConcurrentHashMap<String, List<Candidate>>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    init { loadPatches() }

    fun apiKey(): String = prefs.getString(API_KEY, "").orEmpty()
    fun saveApiKey(value: String) { prefs.edit().putString(API_KEY, value.trim()).apply() }
    fun candidates(pinyin: String): List<Candidate> = patches[pinyin.trim().lowercase(Locale.ROOT)].orEmpty()

    fun requestIfNeeded(pinyin: String, onUpdated: () -> Unit = {}) {
        val key = pinyin.trim().lowercase(Locale.ROOT)
        if (key.length < 2 || apiKey().isBlank() || !inFlight.add(key)) return
        executor.execute {
            val requestStart = System.nanoTime()
            try {
                fetch(key)?.takeIf { it.isNotEmpty() }?.let { result ->
                    patches[key] = result
                    savePatches()
                    Log.d(TAG, "AI patch saved pinyin=$key count=${result.size} top=${result.take(9).map { it.text }}")
                    Handler(Looper.getMainLooper()).post(onUpdated)
                }
                ImeTelemetry.record("deepseek_request", System.nanoTime() - requestStart, key.length, "ok")
            } catch (e: Exception) {
                ImeTelemetry.record("deepseek_request", System.nanoTime() - requestStart, key.length, "error")
                Log.w(TAG, "AI patch request failed pinyin=$key", e)
            } finally { inFlight.remove(key) }
        }
    }

    private fun fetch(pinyin: String): List<Candidate>? {
        val prefix = buildString {
            append("这是一个IME输入库，使用拼音key对应中文候选。")
            append("只输出候选词，用中文逗号分隔；不要解释、编号、拼音或其他标记；最多12个候选。\n")
            append(FEW_SHOT.joinToString(".")); append("."); append(pinyin); append("->")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是中文拼音输入法词库补全器。严格遵守输出格式。"))
            .put(JSONObject().put("role", "user").put("content", "根据few-shot词库规律补全目标拼音，只返回候选词列表。"))
            .put(JSONObject().put("role", "assistant").put("content", prefix).put("prefix", true))
        val body = JSONObject().put("model", MODEL).put("messages", messages)
            .put("max_tokens", 128).put("temperature", 0.2)
            .put("thinking", JSONObject().put("type", "disabled")).toString()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 4000; readTimeout = 8000; doOutput = true
            setRequestProperty("Authorization", "Bearer ${apiKey()}")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w(TAG, "DeepSeek HTTP status=$status body=${response.take(300)}")
                null
            } else parseCandidates(JSONObject(response).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty(), pinyin)
        } finally { connection.disconnect() }
    }

    private fun parseCandidates(raw: String, pinyin: String): List<Candidate> {
        val values = raw.replace("\n", "").replace("。", "").trim()
            .split(',', '，', '、', ';', '；')
            .map { it.trim().trim('"', '\'', '`', ' ') }
            .filter { it.isNotEmpty() && it.length <= 8 }
            .filter { value -> value.all { ch -> ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF } }
            .distinct().take(12)
        return values.mapIndexed { index, text -> Candidate(text, pinyin, 1000 - index) }
    }

    private fun loadPatches() {
        if (!patchFile.exists()) return
        try {
            val root = JSONObject(patchFile.readText(Charsets.UTF_8))
            root.keys().forEach { key ->
                val array = root.optJSONArray(key) ?: return@forEach
                val list = buildList {
                    for (i in 0 until array.length()) {
                        val text = array.optString(i)
                        if (text.isNotBlank()) add(Candidate(text, key, 1000 - i))
                    }
                }
                if (list.isNotEmpty()) patches[key] = list
            }
            Log.d(TAG, "AI patches loaded count=${patches.size}")
        } catch (e: Exception) { Log.w(TAG, "AI patch file invalid; ignoring", e) }
    }

    private fun savePatches() {
        val root = JSONObject()
        patches.entries.sortedBy { it.key }.forEach { (key, values) -> root.put(key, JSONArray(values.map { it.text })) }
        patchFile.writeText(root.toString(), Charsets.UTF_8)
    }
}