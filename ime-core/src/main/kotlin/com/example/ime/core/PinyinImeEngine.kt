package com.example.ime.core

class PinyinImeEngine(
    private val dictionary: RimeDictionary,
    private val aiCandidates: (String) -> List<Candidate> = { emptyList() },
    private val telemetry: (String, Long, Int) -> Unit = { _, _, _ -> },
) {
    fun localCandidates(pinyin: String, limit: Int = 5): List<Candidate> {
        val start = System.nanoTime()
        val exactStart = System.nanoTime()
        val exact = dictionary.candidates(pinyin, limit * 2)
        telemetry("rime_exact", System.nanoTime() - exactStart, exact.size)
        val value = if (exact.isNotEmpty()) {
            exact.take(limit)
        } else {
            val prefixStart = System.nanoTime()
            val prefix = dictionary.candidatesForPrefix(pinyin, limit * 2)
            telemetry("rime_prefix", System.nanoTime() - prefixStart, prefix.size)
            prefix.take(limit)
        }
        telemetry("rime_local", System.nanoTime() - start, value.size)
        return value
    }

    fun remoteCandidates(pinyin: String, limit: Int = 8): List<Candidate> {
        val start = System.nanoTime()
        val value = aiCandidates(pinyin).take(limit)
        telemetry("ai_candidates", System.nanoTime() - start, value.size)
        return value
    }

    fun candidates(pinyin: String, limit: Int = 9): List<Candidate> {
        val local = localCandidates(pinyin, limit * 2)
        val ai = remoteCandidates(pinyin, limit * 2)
        if (ai.isEmpty()) return local.take(limit)
        // Keep the local dictionary as the canonical ordering so Android behaves
        // like the desktop REPL. AI suggestions are an optional extension, not
        // a replacement for the deterministic local candidates.
        return (local + ai).distinctBy { it.text }.take(limit)
    }
}
