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
        ).joinToString("\n")

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

    @Test
    fun consonantSegmentationTakesCartesianProduct() {
        val yaml = listOf(
            "---",
            "name: test",
            "...",
            "我\two\t100",
            "不\tb\t90",
            "吧\tb\t80",
            "版权\tban quan\t1000",
            "在\tz\t70",
            "的\td\t60",
            "可\tk\t100",
            "到\tdao\t90",
            "道\tdao\t80",
        ).joinToString("\n")

        val dictionary = RimeDictionary.fromStreams(listOf(yaml.byteInputStream()))

        assertEquals(
            listOf("我不在到", "我不在道"),
            dictionary.candidatesByConsonantSegmentation("wbzd", limit = 2, perSegmentLimit = 2).map { it.text },
        )
        assertEquals("可到", dictionary.candidatesByConsonantSegmentation("kdao").first().text)
        assertEquals(
            listOf("我不", "我吧"),
            dictionary.candidatesByConsonantSegmentation("wb", limit = 9, perSegmentLimit = 9)
                .map { it.text },
        )
    }

    @Test
    fun engineUsesSegmentationOnlyAfterExactAndPrefixMiss() {
        val yaml = listOf(
            "---",
            "name: test",
            "...",
            "我\two\t100",
            "不\tb\t90",
            "在\tz\t70",
            "的\td\t60",
        ).joinToString("\n")
        val dictionary = RimeDictionary.fromStreams(listOf(yaml.byteInputStream()))
        val engine = PinyinImeEngine(dictionary)

        assertEquals(listOf("我不在的"), engine.localCandidates("wbzd").map { it.text })
        assertEquals(listOf("我"), engine.localCandidates("wo").map { it.text })
    }
}
