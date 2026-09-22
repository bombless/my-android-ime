package com.example.ime.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RimeDictionaryTest {
    @Test
    fun parsesRimeRowsAndRanksByWeight() {
        val yaml = listOf(
            "---",
            "name: test",
            "sort: by_weight",
            "...",
            "我	wo	10",
            "我们	wo men	20",
            "我	wo	30",
        ).joinToString("
")

        val dictionary = RimeDictionary.fromStreams(listOf(yaml.byteInputStream()))
        val candidates = dictionary.candidates("wo")

        assertEquals(listOf("我", "我们"), candidates.map { it.text })
        assertEquals(30, candidates.first().weight)
    }

    @Test
    fun matchesContiguousPinyinAgainstSpacedRimeSyllables() {
        val yaml = listOf(
            "---",
            "name: test",
            "...",
            "你好\tni hao\t100",
        ).joinToString("\n")

        val dictionary = RimeDictionary.fromStreams(listOf(yaml.byteInputStream()))

        assertEquals("你好", dictionary.candidates("nihao").first().text)
    }
}
