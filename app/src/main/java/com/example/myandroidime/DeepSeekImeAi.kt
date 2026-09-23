package com.example.myandroidime

import android.content.Context
import android.util.Log
import com.example.ime.core.Candidate
import org.json.JSONArray
import org.json.JSONObject
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
        private const val ENDPOINT_KEY = "endpoint"

        private const val MODEL_KEY = "model"
        private const val ENABLED_KEY = "enabled"
        private const val DEFAULT_MODEL = "DeepSeek-V4.1-Flash"
        private const val DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions"
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
    private val executor = Executors.newCachedThreadPool()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun apiKey(): String = prefs.getString(API_KEY, "").orEmpty()
    fun saveApiKey(value: String) { prefs.edit().putString(API_KEY, value.trim()).apply() }
    fun isEnabled(): Boolean = prefs.getBoolean(ENABLED_KEY, true)
    fun saveEnabled(value: Boolean) { prefs.edit().putBoolean(ENABLED_KEY, value).apply() }
    fun model(): String = prefs.getString(MODEL_KEY, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    fun saveModel(value: String) {
        val selected = if (value == "deepseek-flash") "deepseek-flash" else DEFAULT_MODEL
        prefs.edit().putString(MODEL_KEY, selected).apply()
    }
    fun endpoint(): String = prefs.getString(ENDPOINT_KEY, DEFAULT_ENDPOINT).orEmpty()
    fun saveEndpoint(value: String) {
        val normalized = value.trim().trimEnd('/')
        val endpoint = when {
            normalized.isBlank() -> DEFAULT_ENDPOINT
            normalized.endsWith("/v1") -> "$normalized/chat/completions"
            normalized.endsWith("/chat/completions") -> normalized
            else -> normalized
        }
        prefs.edit().putString(ENDPOINT_KEY, endpoint).apply()
    }

    /**
     * All DeepSeek suggestions use the standard Chat Completions API.
     * Pinyin conversion and context continuation are both represented as
     * structured tool calls; no pinyin-specific patch/cache is used.
     */
    fun requestIfNeeded(context: String, pinyin: String, onUpdated: (List<Candidate>) -> Unit = {}) {
        val normalizedContext = context.trim()
        val normalizedPinyin = pinyin.trim().lowercase(Locale.ROOT)
        val key = cacheKey(normalizedContext, normalizedPinyin)
        Log.d(TAG, "DeepSeek requestIfNeeded context='${normalizedContext.takeLast(80)}' pinyin='$normalizedPinyin' mode=${if (normalizedPinyin.isEmpty()) "continuation" else "pinyin"}")
        if (apiKey().isBlank()) { Log.w(TAG, "DeepSeek SKIP reason=api_key_missing"); return }
        if (!inFlight.add(key)) { Log.d(TAG, "DeepSeek SKIP reason=already_in_flight"); return }
        Log.d(TAG, "DeepSeek REQUEST queued context='${normalizedContext.takeLast(80)}' pinyin='$normalizedPinyin'")
        executor.execute {
            val requestStart = System.nanoTime()
            try {
                Log.d(TAG, "DeepSeek FETCH start endpoint=${endpoint()} model=${model()}")
                val fetched = fetch(normalizedContext, normalizedPinyin)
                Log.d(TAG, "DeepSeek FETCH returned count=${fetched?.size ?: -1} values=${fetched?.take(12)?.map { it.text }}")
                fetched?.takeIf { it.isNotEmpty() }?.let { result ->
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
        val mode = if (pinyin.isBlank()) "continuation" else "pinyin"
        val prompt = buildString {
            append("你是中文输入法候选生成器。")
            if (mode == "pinyin") {
                append("当前是拼音输入模式：根据拼音和已有中文上下文，给出最可能对应的中文词或短语。")
                append("候选应完整对应当前拼音，不要把拼音本身返回给用户。")
            } else {
                append("当前是连续补全模式：给出已有中文上下文之后最可能出现的中文续写词或短语。")
                append("候选必须是上下文之后新增的内容，绝对不要重复上下文。")
            }
            append("必须直接调用 suggest_candidates 工具返回候选，不要输出自然语言。\n")
            append("已有上下文：").append(context.takeLast(120)).append("\n")
            append("当前拼音：").append(pinyin.ifBlank { "无新的拼音" }).append("\n")
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是中文输入法候选生成器。必须调用 suggest_candidates 工具。"))
            .put(JSONObject().put("role", "user").put("content", prompt))
        val tool = JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "suggest_candidates")
                .put("description", "返回中文输入法候选。拼音模式返回匹配当前拼音的中文词或短语；连续补全模式只返回上下文之后新增的中文续写。")
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
        val body = JSONObject().put("model", model()).put("messages", messages)
            .put("tools", JSONArray().put(tool))
            .put("tool_choice", JSONObject().put("type", "function").put("function", JSONObject().put("name", "suggest_candidates")))
            .put("max_tokens", 256).put("temperature", 0.2)
            .put("thinking", JSONObject().put("type", "disabled")).toString()
        logLarge("DeepSeek HTTP REQUEST BODY", body)

        val connection = (URL(endpoint()).openConnection() as HttpURLConnection).apply {
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
                    val suffix = if (pinyin.isNotBlank()) value else value.removePrefix(context).trim()
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

}