package com.example.ime.core

import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class RimeDictionary private constructor(
    private val byPinyin: Map<String, List<Candidate>>,
) {
    fun candidates(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        return byPinyin[key].orEmpty().take(limit)
    }

    fun candidatesForPrefix(input: String, limit: Int = 9): List<Candidate> {
        val key = input.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) return emptyList()
        return byPinyin.asSequence()
            .filter { it.key.startsWith(key) }
            .flatMap { it.value.asSequence() }
            .sortedByDescending { it.weight }
            .take(limit)
            .toList()
    }

    companion object {
        fun fromFiles(paths: List<Path>): RimeDictionary =
            paths.fold(Builder()) { builder, path ->
                Files.newBufferedReader(path).use(builder::read)
                builder
            }.build()

        fun fromStreams(streams: List<InputStream>): RimeDictionary =
            streams.fold(Builder()) { builder, stream ->
                stream.bufferedReader(Charsets.UTF_8).use(builder::read)
                builder
            }.build()

        private class Builder {
            private val entries = HashMap<String, MutableList<Candidate>>()

            fun read(reader: BufferedReader): Builder {
                var inData = false
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line == "...") {
                        inData = true
                        return@forEachLine
                    }
                    if (!inData || line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val fields = line.split('	')
                    if (fields.size < 2) return@forEachLine
                    val word = fields[0]
                    val pinyin = fields[1].trim().lowercase(Locale.ROOT)
                    val weight = fields.getOrNull(2)?.toIntOrNull() ?: 0
                    if (word.isNotEmpty() && pinyin.isNotEmpty()) {
                        entries.getOrPut(pinyin) { mutableListOf() }
                            .add(Candidate(word, pinyin, weight))
                    }
                }
                return this
            }

            fun build(): RimeDictionary {
                val normalized = entries.mapValues { (_, list) ->
                    list.distinctBy { it.text }.sortedByDescending { it.weight }
                }
                return RimeDictionary(normalized)
            }
        }
    }
}
