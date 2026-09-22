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
    fun clearAllPatches() {
        patches.clear()
        if (patchFile.exists()) patchFile.delete()
        Log.d(TAG, "DeepSeek ALL PATCHES CLEARED")
    }
    fun candidates(pinyin: String): List<Candidate> = patches[pinyin.trim().lowercase(Locale.ROOT)].orEmpty()

    fun candidates(context: String, pinyin: String): List<Candidate> = patches[cacheKey(context, pinyin)].orEmpty()

    /**
     * Pinyin-mode requests are cacheable. Completion requests (empty pinyin)
     * are deliberately never read from or written to the persistent cache.
     */
    fun requestIfNeeded(context: String, pinyin: String, onUpdated: (List<Candidate>) -> Unit = {}) {
        val normalizedContext = context.trim()
        val normalizedPinyin = pinyin.trim().lowercase(Locale.ROOT)
        val key = cacheKey(normalizedContext, normalizedPinyin)
        val cacheable = normalizedPinyin.isNotEmpty()
        val cached = if (cacheable) patches[key].orEmpty() else emptyList()
        val cacheHit = cacheable && cached.isNotEmpty()
        Log.d(TAG, "DeepSeek requestIfNeeded context='${normalizedContext.takeLast(80)}' pinyin='$normalizedPinyin' mode=${if (cacheable) "pinyin_cacheable" else "completion_no_cache"} cacheHit=$cacheHit cachedCount=${cached.size} cachedValues=${cached.take(12).map { it.text }}")
        if (apiKey().isBlank()) { Log.w(TAG, "DeepSeek SKIP reason=api_key_missing"); return }
        if (cacheHit) {
            Log.d(TAG, "DeepSeek SKIP reason=cache_hit cachedCount=${cached.size} cachedValues=${cached.take(12).map { it.text }}")
            Handler(Looper.getMainLooper()).post { onUpdated(cached) }
            return
        }
        if (!inFlight.add(key)) { Log.d(TAG, "DeepSeek SKIP reason=already_in_flight"); return }
        Log.d(TAG, "DeepSeek REQUEST queued context='${normalizedContext.takeLast(80)}' pinyin='$normalizedPinyin' cacheWrite=$cacheable")
        executor.execute {
            val requestStart = System.nanoTime()
            try {
                Log.d(TAG, "DeepSeek FETCH start endpoint=$ENDPOINT model=$MODEL")
                val fetched = fetch(normalizedContext, normalizedPinyin)
                Log.d(TAG, "DeepSeek FETCH returned count=${fetched?.size ?: -1} values=${fetched?.take(12)?.map { it.text }}")
                fetched?.takeIf { it.isNotEmpty() }?.let { result ->
                    if (cacheable) {
                        patches[key] = result
                        savePatches()
                        Log.d(TAG, "DeepSeek PATCH saved context='${normalizedContext.takeLast(40)}' pinyin=$normalizedPinyin count=${result.size} top=${result.take(9).map { it.text }}")
                    } else {
                        Log.d(TAG, "DeepSeek COMPLETION result not cached context='${normalizedContext.takeLast(40)}' count=${result.size} top=${result.take(9).map { it.text }}")
                    }
                    Handler(Looper.getMainLooper()).post { onUpdated(result) }
                } ?: Log.w(TAG, "DeepSeek FETCH produced no candidates pinyin=$normalizedPinyin")
                ImeTelemetry.record("deepseek_request", System.nanoTime() - requestStart, normalizedPinyin.length, "ok")
            } catch (e: Exception) {
                ImeTelemetry.record("deepseek_request", System.nanoTime() - requestStart, normalizedPinyin.length, "error")
                Log.e(TAG, "DeepSeek REQUEST failed context='${normalizedContext.takeLast(40)}' pinyin=$normalizedPinyin", e)
            } finally { inFlight.remove(key) }
        }
    }

    private fun fetch(context: String, pinyin: String): List<Candidate>? {
        val prompt = buildString {
            append("你是中文输入法的连续补全器。")
            append("根据已有中文上下文和当前输入，给出最可能的中文续写候选。")
            append("候选必须是用户已有上下文之后新增的词或短语，绝对不要重复上下文。")
            append("直接调用 suggest_candidates 工具返回候选，不要输出自然语言。\n")
            append("已有上下文：").append(context.takeLast(120)).append("\n")
            append("当前输入：").append(pinyin.ifBlank { "无新的拼音，直接预测上下文后的续写" }).append("\n")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是中文输入法候选生成器。必须调用 suggest_candidates 工具。"))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val tool = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "suggest_candidates")
                .put("description", "返回中文输入法的续写候选。只返回上下文之后新增的中文词或短语。")
                .put("strict", true)
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("candidates", JSONObject()
                            .put("type", "array")
                            .put("description", "按概率从高到低排列的续写候选，最多12个")
                            .put("items", JSONObject().put("type", "string"))))
                    .put("required", JSONArray().put("candidates"))
                    .put("additionalProperties", false)))
        val body = JSONObject().put("model", MODEL).put("messages", messages)
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "suggest_candidates")))
            .put("max_tokens", 256).put("temperature", 0.2)
            .put("thinking", JSONObject().put("type", "disabled")).toString()
        logLarge("DeepSeek HTTP REQUEST BODY", body)

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
            Log.d(TAG, "DeepSeek HTTP RESPONSE status=$status bodyChars=${response.length}")
            logLarge("DeepSeek HTTP RESPONSE BODY", response)
            if (status !in 200..299) {
                Log.e(TAG, "DeepSeek HTTP FAILED status=$status")
                null
            } else {
                val message = JSONObject(response).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")
                Log.d(TAG, "DeepSeek messagePresent=${message != null}")
                val toolCalls = message?.optJSONArray("tool_calls")
                Log.d(TAG, "DeepSeek toolCalls count=${toolCalls?.length() ?: 0}")
                val arguments = toolCalls?.optJSONObject(0)
                    ?.optJSONObject("function")?.optString("arguments").orEmpty()
                Log.d(TAG, "DeepSeek tool arguments chars=${arguments.length} preview=${arguments.take(500)}")
                parseToolCandidates(arguments, context, pinyin)
            }
        } finally { connection.disconnect() }
    }

    private fun logLarge(label: String, text: String) {
        val chunkSize = 3000
        if (text.isEmpty()) {
            Log.d(TAG, "$label [empty]")
            return
        }
        var start = 0
        var part = 1
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            Log.d(TAG, "$label part=$part chars=${text.length} range=$start..${end - 1}: ${text.substring(start, end)}")
            start = end
            part++
        }
    }

    private fun parseToolCandidates(rawArguments: String, context: String, pinyin: String): List<Candidate> {
        if (rawArguments.isBlank()) {
            Log.w(TAG, "DeepSeek parse EMPTY tool arguments pinyin='$pinyin'")
            return emptyList()
        }
        val array = try {
            JSONObject(rawArguments).optJSONArray("candidates")
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek parse INVALID tool arguments=$rawArguments", e)
            null
        } ?: run {
            Log.w(TAG, "DeepSeek parse missing candidates array arguments=$rawArguments")
            return emptyList()
        }
        Log.d(TAG, "DeepSeek parse rawCandidates count=${array.length()} context='${context.takeLast(80)}' pinyin='$pinyin'")
        val values = buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty() && value.length <= 8) {
                    val suffix = value.removePrefix(context).trim()
                    val accepted = suffix.isNotEmpty() && suffix.all { ch ->
                        ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF
                    }
                    Log.d(TAG, "DeepSeek candidate[$i] raw='$value' suffix='$suffix' accepted=$accepted")
                    if (accepted) add(suffix)
                } else {
                    Log.d(TAG, "DeepSeek candidate[$i] rejected raw='$value' reason=blank_or_too_long")
                }
            }
        }.distinct().take(12)
        Log.d(TAG, "DeepSeek parse FINAL count=${values.size} values=$values")
        return values.mapIndexed { index, text -> Candidate(text, pinyin, 1000 - index) }
    }

    private fun cacheKey(context: String, pinyin: String): String =
        context.trim() + "\u0000" + pinyin.trim().lowercase(Locale.ROOT)

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