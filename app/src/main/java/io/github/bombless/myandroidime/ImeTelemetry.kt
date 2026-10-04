package io.github.bombless.myandroidime

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

object ImeTelemetry {
    private const val TAG = "ImeTelemetry"
    private const val PORT = 8765
    private const val MAX_EVENTS = 2000

    data class Event(
        val id: Long,
        val at: Long,
        val name: String,
        val durationMs: Double,
        val size: Int,
        val status: String,
    )

    data class SlowRimeSearch(val pinyin: String, val durationMs: Double, val at: Long)

    private const val SEARCH_PREFS = "rime_slow_searches"
    private const val SEARCH_KEY = "top_three"

    /** Keeps the three slowest local Rime lookups on-device for the settings screen. */
    fun recordRimeSearch(context: Context, pinyin: String, durationNs: Long) {
        if (pinyin.isBlank()) return
        val prefs = context.applicationContext.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE)
        synchronized(lock) {
            val current = readSlowSearches(prefs.getString(SEARCH_KEY, null))
            val updated = (current + SlowRimeSearch(pinyin, durationNs.coerceAtLeast(0L) / 1_000_000.0, System.currentTimeMillis()))
                .sortedByDescending { it.durationMs }
                .take(3)
            val json = JSONArray().apply {
                updated.forEach { item ->
                    put(JSONObject().put("pinyin", item.pinyin).put("durationMs", item.durationMs).put("at", item.at))
                }
            }
            prefs.edit().putString(SEARCH_KEY, json.toString()).apply()
        }
    }

    fun slowestRimeSearches(context: Context): List<SlowRimeSearch> {
        val prefs = context.applicationContext.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE)
        return synchronized(lock) { readSlowSearches(prefs.getString(SEARCH_KEY, null)).sortedByDescending { it.durationMs }.take(3) }
    }

    private fun readSlowSearches(raw: String?): List<SlowRimeSearch> = runCatching {
        val array = JSONArray(raw ?: "[]")
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(SlowRimeSearch(item.optString("pinyin"), item.optDouble("durationMs", 0.0), item.optLong("at", 0L)))
            }
        }
    }.getOrDefault(emptyList())

    private val nextId = AtomicLong(1)
    private val lock = Any()
    private val events = ArrayDeque<Event>()
    @Volatile private var running = false
    @Volatile private var server: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        synchronized(lock) {
            if (running) return
            try {
                Log.i(TAG, "telemetry HTTP binding 127.0.0.1:$PORT")
                server = ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"))
                running = true
                thread = Thread(::serve, "ime-telemetry").apply {
                    isDaemon = true
                    start()
                }
                Log.i(TAG, "telemetry HTTP listening on ${server?.localSocketAddress}")
            } catch (e: Exception) {
                running = false
                server?.close()
                server = null
                Log.e(TAG, "telemetry HTTP failed to bind 127.0.0.1:$PORT: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            try { server?.close() } catch (_: Exception) {}
            server = null
            thread = null
            Log.i(TAG, "telemetry HTTP stopped")
        }
    }

    fun record(name: String, durationNs: Long = 0L, size: Int = 0, status: String = "ok") {
        val event = Event(nextId.getAndIncrement(), System.currentTimeMillis(), name, durationNs / 1_000_000.0, size, status)
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) events.removeFirst()
            events.addLast(event)
        }
    }

    fun clear() {
        synchronized(lock) {
            events.clear()
            nextId.set(1)
        }
        Log.i(TAG, "telemetry data cleared")
    }

    private fun serve() {
        Log.i(TAG, "telemetry accept loop started on 127.0.0.1:$PORT")
        while (running) {
            try {
                val socket = server?.accept() ?: break
                Log.d(TAG, "telemetry client connected from ${socket.remoteSocketAddress}")
                Thread({ handle(socket) }, "ime-telemetry-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: SocketException) {
                if (running) Log.w(TAG, "telemetry server socket closed unexpectedly")
            } catch (e: Exception) {
                if (running) Log.w(TAG, "telemetry accept failed", e)
            }
        }
        Log.i(TAG, "telemetry accept loop exited")
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val request = reader.readLine().orEmpty()
            while (reader.readLine()?.isNotEmpty() == true) {}
            val path = request.split(' ').getOrNull(1)?.substringBefore('?').orEmpty()
            when (path) {
                "/", "/telemetry" -> send(s.getOutputStream(), "application/json; charset=utf-8", json().toString(), false)
                "/telemetry.csv" -> send(s.getOutputStream(), "text/csv; charset=utf-8", csv(), true)
                else -> send(s.getOutputStream(), "text/plain; charset=utf-8", "Not Found", false, 404)
            }
        }
    }

    private fun json(): JSONObject {
        val snapshot = synchronized(lock) { events.toList() }
        val byName = snapshot.groupBy { it.name }.mapValues { (_, values) ->
            val sorted = values.map { it.durationMs }.sorted()
            JSONObject().apply {
                put("count", values.size)
                put("avgMs", sorted.averageOrZero())
                put("p50Ms", sorted.percentile(0.50))
                put("p95Ms", sorted.percentile(0.95))
                put("maxMs", sorted.maxOrNull() ?: 0.0)
            }
        }
        return JSONObject().apply {
            put("port", PORT)
            put("eventCount", snapshot.size)
            put("summary", JSONObject().apply { byName.forEach { (k, v) -> put(k, v) } })
            put("events", JSONArray().apply {
                snapshot.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("at", e.at)
                        put("name", e.name)
                        put("durationMs", e.durationMs)
                        put("size", e.size)
                        put("status", e.status)
                    })
                }
            })
        }
    }

    private fun csv(): String {
        val snapshot = synchronized(lock) { events.toList() }
        return buildString {
            appendLine("id,at,name,durationMs,size,status")
            snapshot.forEach { e ->
                append(e.id).append(',')
                    .append(e.at).append(',')
                    .append(csvField(e.name)).append(',')
                    .append(e.durationMs).append(',')
                    .append(e.size).append(',')
                    .append(csvField(e.status)).appendLine()
            }
        }
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    private fun List<Double>.percentile(p: Double): Double {
        if (isEmpty()) return 0.0
        val index = ((size - 1) * p).toInt().coerceIn(0, lastIndex)
        return this[index]
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private fun send(output: OutputStream, contentType: String, body: String, attachment: Boolean, code: Int = 200) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val headers = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            if (attachment) append("Content-Disposition: attachment; filename=\"ime-telemetry.csv\"\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
