package com.example.ime.core

import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RimeDictionary private constructor(
    private val byPinyin: MutableMap<String, MutableList<Candidate>>,
    private val shardSource: ShardSource? = null,
) {
    private val byCompactPinyin = HashMap<String, MutableList<Candidate>>()
    private val compactKeys = ArrayList<String>()
    private val loadedInitials = HashSet<Char>()
    private val preloadStarted = AtomicBoolean(false)
    @Volatile private var preloadComplete = shardSource == null

    init {
        rebuildCompactIndex()
    }

    /**
     * Builds the compact lookup index once after bulk loading instead of rebuilding
     * it for every shard. This keeps dictionary I/O and index construction off the
     * input/key path.
     */
    private fun rebuildCompactIndex() {
        byCompactPinyin.clear()
        byPinyin.forEach { (pinyin, candidates) ->
            byCompactPinyin.getOrPut(pinyin.replace(" ", "")) { mutableListOf() }.addAll(candidates)
        }
        compactKeys.clear()
        compactKeys.addAll(byCompactPinyin.keys.sorted())
    }

    fun candidates(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        if (!preloadComplete) return emptyList()
        return (byPinyin[key] ?: byCompactPinyin[key.replace(" ", "")]).orEmpty().take(limit)
    }

    fun candidatesForPrefix(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty() || !preloadComplete) return emptyList()
        if (!key.contains(' ')) {
            val compactKey = key.replace(" ", "")
            val start = lowerBound(compactKeys, compactKey)
            val end = lowerBound(compactKeys, compactKey + '\uffff')
            return compactKeys.subList(start, end)
                .asSequence()
                .flatMap { byCompactPinyin[it].orEmpty().asSequence() }
                .sortedByDescending { it.weight }
                .take(limit)
                .toList()
        }
        return byPinyin.keys
            .asSequence()
            .filter { it.startsWith(key) }
            .flatMap { byPinyin[it].orEmpty().asSequence() }
            .sortedByDescending { it.weight }
            .take(limit)
            .toList()
    }

    /**
     * Cuts compact pinyin at consonant initials and takes the Cartesian product
     * of exact dictionary candidates for the resulting chunks. zh/ch/sh are
     * treated as two-letter initials. This is intended only as a fallback after
     * normal exact and prefix lookup has returned no candidates.
     */
    fun candidatesByConsonantSegmentation(
        input: String,
        limit: Int = 9,
        perSegmentLimit: Int = 9,
    ): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT).replace(" ", "")
        if (key.isEmpty() || !preloadComplete || limit <= 0 || perSegmentLimit <= 0) return emptyList()

        val boundaries = ArrayList<Int>()
        var index = 0
        while (index < key.length) {
            if (index > 0 && initialLengthAt(key, index) > 0) boundaries += index
            index += initialLengthAt(key, index).coerceAtLeast(1)
        }
        if (boundaries.isEmpty()) return emptyList()

        val segments = ArrayList<String>(boundaries.size + 1)
        var start = 0
        boundaries.forEach { end ->
            segments += key.substring(start, end)
            start = end
        }
        segments += key.substring(start)
        if (segments.any { it.isEmpty() }) return emptyList()

        data class Partial(val text: String, val weight: Int, val pinyin: String)
        var partials = listOf(Partial("", 0, ""))
        for (segment in segments) {
            val candidates = candidatesForSegment(segment, perSegmentLimit)
            if (candidates.isEmpty()) return emptyList()

            partials = partials.asSequence()
                .flatMap { prefix ->
                    candidates.asSequence().map { candidate ->
                        Partial(
                            prefix.text + candidate.text,
                            prefix.weight + candidate.weight,
                            if (prefix.pinyin.isEmpty()) segment else prefix.pinyin + " " + segment,
                        )
                    }
                }
                .sortedByDescending { it.weight }
                .distinctBy { it.text }
                .take(limit)
                .toList()
        }

        return partials.map { Candidate(it.text, it.pinyin, it.weight) }
    }

    private fun candidatesForSegment(segment: String, limit: Int): List<Candidate> {
        val candidates = if (segment.length == 1 && initialLengthAt(segment, 0) == 1) {
            // A one-letter initial is a prefix lookup (e.g. "b" -> "ba", "bu").
            candidatesForPrefix(segment, limit * 4)
        } else {
            (byPinyin[segment].orEmpty() + byCompactPinyin[segment].orEmpty())
                .sortedByDescending { it.weight }
                .distinctBy { it.text }
        }
        return candidates
            // Cartesian-product sampling is character based: never let a
            // multi-character word such as "版权" occupy the "b" slot.
            .filter { it.text.codePointCount(0, it.text.length) == 1 }
            .sortedByDescending { it.weight }
            .distinctBy { it.text }
            .take(limit)
    }

    private fun initialLengthAt(input: String, index: Int): Int {
        if (index >= input.length) return 0
        if (index + 1 < input.length && input.substring(index, index + 2) in setOf("zh", "ch", "sh")) {
            return 2
        }
        return if (input[index] in "bpmfdtnlgkhjqxzcsryw") 1 else 0
    }
    private fun lowerBound(values: List<String>, target: String): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /**
     * Loads every shard once on a background thread. The old implementation did
     * this lazily from candidates(), which made the first key for each initial block
     * on the IME input path for hundreds of milliseconds.
     */
    fun preloadAllAsync(onComplete: (() -> Unit)? = null) {
        if (shardSource == null || preloadComplete || !preloadStarted.compareAndSet(false, true)) {
            if (preloadComplete) onComplete?.invoke()
            return
        }
        Thread({
            try {
                preloadAll()
            } finally {
                onComplete?.invoke()
            }
        }, "rime-dictionary-preload").apply {
            isDaemon = true
            start()
        }
    }

    private fun preloadAll() {
        synchronized(this) {
            if (preloadComplete) return
            val source = shardSource ?: return
            ('a'..'z').forEach { initial ->
                if (!loadedInitials.add(initial)) return@forEach
                source.list(initial).forEach { path ->
                    source.open(path).bufferedReader(Charsets.UTF_8).use { readInto(it, byPinyin) }
                }
            }
            normalize(byPinyin)
            rebuildCompactIndex()
            preloadComplete = true
        }
    }

    interface ShardSource {
        fun list(initial: Char): List<String>
        fun open(path: String): InputStream
    }

    companion object {
        fun fromFiles(paths: List<Path>): RimeDictionary {
            val entries = HashMap<String, MutableList<Candidate>>()
            paths.forEach { path ->
                Files.newBufferedReader(path).use { readInto(it, entries) }
            }
            normalize(entries)
            return RimeDictionary(entries)
        }

        fun fromStreams(streams: List<InputStream>): RimeDictionary {
            val entries = HashMap<String, MutableList<Candidate>>()
            streams.forEach { stream ->
                stream.bufferedReader(Charsets.UTF_8).use { readInto(it, entries) }
            }
            normalize(entries)
            return RimeDictionary(entries)
        }

        fun fromShards(source: ShardSource): RimeDictionary =
            RimeDictionary(HashMap(), source)

        private fun readInto(
            reader: BufferedReader,
            entries: MutableMap<String, MutableList<Candidate>>,
        ) {
            var inData = false
            reader.forEachLine { raw ->
                val line = raw.trim()
                if (line == "...") {
                    inData = true
                    return@forEachLine
                }
                if (!inData || line.isEmpty() || line.startsWith("#")) return@forEachLine
                val fields = line.split('\t')
                if (fields.size < 2) return@forEachLine
                val word = fields[0]
                val pinyin = fields[1].trim().lowercase(Locale.ROOT)
                val weight = fields.getOrNull(2)?.toIntOrNull() ?: 0
                if (word.isNotEmpty() && pinyin.isNotEmpty()) {
                    entries.getOrPut(pinyin) { mutableListOf() }
                        .add(Candidate(word, pinyin, weight))
                }
            }
        }

        private fun normalize(entries: MutableMap<String, MutableList<Candidate>>) {
            entries.replaceAll { _, list ->
                // Keep the highest-weight duplicate, matching the previous
                // normalize semantics exactly.
                list.sortedByDescending { it.weight }
                    .distinctBy { it.text }
                    .toMutableList()
            }
        }
    }
}
