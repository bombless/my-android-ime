package com.example.ime.core

import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class RimeDictionary private constructor(
    private val byPinyin: MutableMap<String, MutableList<Candidate>>,
    private val shardSource: ShardSource? = null,
) {
    private val loadedInitials = HashSet<Char>()

    fun candidates(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        ensureLoaded(key.first())
        return byPinyin[key].orEmpty().take(limit)
    }

    fun candidatesForPrefix(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        ensureLoaded(key.first())
        return byPinyin.asSequence()
            .filter { it.key.startsWith(key) }
            .flatMap { it.value.asSequence() }
            .sortedByDescending { it.weight }
            .take(limit)
            .toList()
    }

    private fun ensureLoaded(initial: Char) {
        val source = shardSource ?: return
        synchronized(this) {
            if (!loadedInitials.add(initial)) return
            source.list(initial).forEach { path ->
                source.open(path).bufferedReader(Charsets.UTF_8).use { readInto(it, byPinyin) }
            }
            normalize(byPinyin)
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
                list.distinctBy { it.text }.sortedByDescending { it.weight }.toMutableList()
            }
        }
    }
}
