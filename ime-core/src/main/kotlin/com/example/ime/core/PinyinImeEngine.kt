package com.example.ime.core

class PinyinImeEngine(
    private val dictionary: RimeDictionary,
    private val aiCandidates: (String) -> List<Candidate> = { emptyList() },
) {
    fun localCandidates(pinyin: String, limit: Int = 5): List<Candidate> =
        dictionary.candidates(pinyin, limit * 2).ifEmpty {
            dictionary.candidatesForPrefix(pinyin, limit * 2)
        }.take(limit)

    fun remoteCandidates(pinyin: String, limit: Int = 8): List<Candidate> =
        aiCandidates(pinyin).take(limit)

    fun candidates(pinyin: String, limit: Int = 9): List<Candidate> {
        val local = localCandidates(pinyin, limit * 2)
        val ai = remoteCandidates(pinyin, limit * 2)
        if (ai.isEmpty()) return local.take(limit)
        return (ai + local).distinctBy { it.text }.take(limit)
    }
}
