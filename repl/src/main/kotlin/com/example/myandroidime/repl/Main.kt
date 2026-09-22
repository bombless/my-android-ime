package com.example.myandroidime.repl

import com.example.ime.core.PinyinImeEngine
import com.example.ime.core.RimeDictionary
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val root = Path.of(System.getProperty("user.dir"))
    val files = listOf("base.dict.yaml", "ext.dict.yaml", "tencent.dict.yaml")
        .map { root.resolve(".vendor/rime-ice/cn_dicts").resolve(it) }
        .filter(Files::exists)

    require(files.isNotEmpty()) {
        "找不到 Rime Ice 词库，请先把 iDvel/rime-ice 放到 .vendor/rime-ice"
    }

    val engine = PinyinImeEngine(RimeDictionary.fromFiles(files))
    println("Chinese IME REPL — 输入拼音后回车，输入 :q 退出")
    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        if (input == ":q") break
        val candidates = engine.candidates(input)
        if (candidates.isEmpty()) {
            println("(无候选)")
        } else {
            candidates.forEachIndexed { index, candidate ->
                println("${index + 1}${candidate.text}  [${candidate.pinyin}, ${candidate.weight}]")
            }
        }
    }
}
