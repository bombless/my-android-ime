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
    private val precomputedSegmentation: Map<String, List<Candidate>> = emptyMap(),
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
     * Tries compact-pinyin syllable cuts from fewer syllables to more syllables.
     *
     * The old implementation cut at every consonant, which made ambiguous input
     * such as "rran" become "r / r / an" and therefore missed the useful
     * "r / ran" interpretation. We now enumerate consonant-boundary partitions
     * by segment count: all 2-syllable interpretations are tried first, then 3,
     * etc. A later level is only used when the earlier level cannot fill `limit`.
     */
    fun candidatesByConsonantSegmentation(
        input: String,
        limit: Int = 9,
        perSegmentLimit: Int = 9,
    ): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT).replace(" ", "")
        if (key.isEmpty() || !preloadComplete || limit <= 0 || perSegmentLimit <= 0) return emptyList()
        precomputedSegmentation[key]?.let { return it.take(limit) }

        val boundaries = ArrayList<Int>()
        var index = 0
        while (index < key.length) {
            if (index > 0 && initialLengthAt(key, index) > 0) boundaries += index
            index += initialLengthAt(key, index).coerceAtLeast(1)
        }
        if (boundaries.isEmpty()) return emptyList()

        data class Partial(val text: String, val weight: Int, val pinyin: String)
        val maxSegments = boundaries.size + 1
        val accumulated = mutableListOf<Partial>()
        for (segmentCount in 2..maxSegments) {
            val results = mutableListOf<Partial>()
            enumerateSegmentations(
                key, boundaries, segmentCount, 0, IntArray(segmentCount - 1), 0
            ) { segments ->
                if (segmentCount == 2) {
                    val wildcardResults = candidatesForTwoSyllableWildcard(segments, limit)
                    if (wildcardResults.isNotEmpty()) {
                        results += wildcardResults.map { candidate ->
                            Partial(candidate.text, candidate.weight, candidate.pinyin)
                        }
                        return@enumerateSegmentations
                    }
                }
                var partials = listOf(Partial("", 0, ""))
                for (segment in segments) {
                    val candidates = candidatesForSegment(segment, perSegmentLimit)
                    if (candidates.isEmpty()) return@enumerateSegmentations
                    partials = partials.asSequence()
                        .flatMap { prefix -> candidates.asSequence().map { candidate ->
                            Partial(
                                prefix.text + candidate.text,
                                prefix.weight + candidate.weight,
                                if (prefix.pinyin.isEmpty()) segment else prefix.pinyin + " " + segment,
                            )
                        } }
                        .sortedByDescending { it.weight }
                        .distinctBy { it.text }
                        .take(limit)
                        .toList()
                }
                results += partials
            }
            val ranked = results.sortedByDescending { it.weight }.distinctBy { it.text }
            for (partial in ranked) {
                if (accumulated.none { it.text == partial.text }) accumulated += partial
            }
            if (accumulated.size >= limit) break
        }
        return accumulated.take(limit).map { Candidate(it.text, it.pinyin, it.weight) }
    }

    /**
     * Matches a two-syllable interpretation against whole dictionary entries.
     *
     * For an initial-only first segment such as "r" followed by "ran", the
     * pattern is conceptually "r*ran": the wildcard must consume exactly one
     * complete syllable. This deliberately returns the dictionary word itself
     * instead of combining one character candidate for "r" with another one for
     * "ran".
     */
    private fun candidatesForTwoSyllableWildcard(
        segments: List<String>,
        limit: Int,
    ): List<Candidate> {
        if (segments.size != 2) return emptyList()
        val prefix = segments[0]
        val suffix = segments[1]
        val prefixIsInitial = prefix.length == 1 && initialLengthAt(prefix, 0) == 1
        if (!prefixIsInitial || suffix.isEmpty()) return emptyList()

        return byPinyin.asSequence()
            .filter { (pinyin, _) ->
                val syllables = pinyin.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
                syllables.size == 2 &&
                    syllables[0].startsWith(prefix) &&
                    syllables[1].startsWith(suffix)
            }
            .flatMap { (_, candidates) -> candidates.asSequence() }
            .sortedByDescending { it.weight }
            .distinctBy { it.text }
            .take(limit)
            .toList()
    }

    private fun enumerateSegmentations(
        key: String,
        boundaries: List<Int>,
        segmentCount: Int,
        startBoundaryIndex: Int,
        chosenBoundaries: IntArray,
        chosenCount: Int,
        consume: (List<String>) -> Unit,
    ) {
        if (chosenCount == chosenBoundaries.size) {
            val segments = ArrayList<String>(segmentCount)
            var start = 0
            chosenBoundaries.forEach { end ->
                segments += key.substring(start, end)
                start = end
            }
            segments += key.substring(start)
            if (segments.none { it.isEmpty() }) consume(segments)
            return
        }
        val remainingCuts = chosenBoundaries.size - chosenCount
        val lastExclusive = boundaries.size - (remainingCuts - 1)
        for (i in startBoundaryIndex until lastExclusive) {
            chosenBoundaries[chosenCount] = boundaries[i]
            enumerateSegmentations(
                key, boundaries, segmentCount, i + 1, chosenBoundaries, chosenCount + 1, consume
            )
        }
    }

    private fun candidatesForSegment(segment: String, limit: Int): List<Candidate> {
        val isConsonantInitial = segment.length == 1 && initialLengthAt(segment, 0) == 1
        val isTwoLetterInitial = segment in setOf("zh", "ch", "sh")
        val candidates = if (isConsonantInitial || isTwoLetterInitial) {
            // Initial-only segments are prefix lookups (e.g. "b" -> "ba", "bu",
            // and "sh" -> "sha", "shen", "shi"). This also supports compact
            // consonant-cut forms such as "weishme".
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

        fun fromShardsWithPrecomputed(source: ShardSource, precomputed: InputStream): RimeDictionary {
            val index = HashMap<String, MutableList<Candidate>>()
            precomputed.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trimEnd()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val fields = line.split('\t')
                    if (fields.size < 4) return@forEach
                    val key = fields[0].trim().lowercase(Locale.ROOT)
                    val word = fields[1]
                    val pinyin = fields[2].trim().lowercase(Locale.ROOT)
                    val weight = fields[3].toIntOrNull() ?: 0
                    if (key.isNotEmpty() && word.isNotEmpty() && pinyin.isNotEmpty()) {
                        index.getOrPut(key) { mutableListOf() }.add(Candidate(word, pinyin, weight))
                    }
                }
            }
            val normalizedIndex = index.mapValues { (_, list) ->
                list.sortedByDescending { it.weight }.distinctBy { it.text }
            }
            return RimeDictionary(HashMap(), source, normalizedIndex)
        }

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
