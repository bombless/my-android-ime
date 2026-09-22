package com.example.ime.core

class PinyinImeEngine(
    private val dictionary: RimeDictionary,
    private val aiCandidates: (String) -> List<Candidate> = { emptyList() },
) {
    fun candidates(pinyin: String, limit: Int = 9): List<Candidate> {
        val local = dictionary.candidates(pinyin, limit * 2).ifEmpty {
            dictionary.candidatesForPrefix(pinyin, limit * 2)
        }
        val ai = aiCandidates(pinyin)
        if (ai.isEmpty()) return local.take(limit)
        return (ai + local).distinctBy { it.text }.take(limit)
    }
}
