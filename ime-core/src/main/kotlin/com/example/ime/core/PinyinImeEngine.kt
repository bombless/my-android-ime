package com.example.ime.core

class PinyinImeEngine(private val dictionary: RimeDictionary) {
    fun candidates(pinyin: String, limit: Int = 9): List<Candidate> =
        dictionary.candidates(pinyin, limit).ifEmpty {
            dictionary.candidatesForPrefix(pinyin, limit)
        }
}
