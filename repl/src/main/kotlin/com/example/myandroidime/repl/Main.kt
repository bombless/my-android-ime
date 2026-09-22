package com.example.myandroidime.repl

import com.example.ime.core.PinyinImeEngine
import com.example.ime.core.RimeDictionary
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset

fun main() {
    val terminalCharset = detectTerminalCharset()
    val input = BufferedReader(InputStreamReader(System.`in`, terminalCharset))
    val output = PrintWriter(OutputStreamWriter(System.out, terminalCharset), true)

    val root = Path.of(System.getProperty("user.dir"))
    val files = listOf(
        "base.dict.yaml",
        "ext.dict.yaml",
        "tencent.dict.yaml",
        "8105.dict.yaml",
        "41448.dict.yaml",
    )
        .map { root.resolve(".vendor/rime-ice/cn_dicts").resolve(it) }
        .filter(Files::exists)

    require(files.isNotEmpty()) {
        "找不到 Rime Ice 词库，请先把 iDvel/rime-ice 放到 .vendor/rime-ice"
    }

    val engine = PinyinImeEngine(RimeDictionary.fromFiles(files))
    output.println("Chinese IME REPL — 终端编码: ${terminalCharset.name()} — 输入拼音后回车，输入 :q 退出")
    while (true) {
        output.print("> ")
        output.flush()
        val line = input.readLine() ?: break
        if (line == ":q") break
        val candidates = engine.candidates(line)
        if (candidates.isEmpty()) {
            output.println("(无候选)")
        } else {
            candidates.forEachIndexed { index, candidate ->
                output.println("${index + 1}${candidate.text}  [${candidate.pinyin}, ${candidate.weight}]")
            }
        }
    }
}

private fun detectTerminalCharset(): Charset {
    // Windows Terminal speaks UTF-8, but the JDK can still report the legacy
    // Windows locale/code page (for example windows-31j). Trusting that value
    // makes Chinese output get decoded with the wrong charset.
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true) &&
        (System.getenv("WT_SESSION") != null || System.getenv("TERM_PROGRAM") == "Windows_Terminal")
    ) {
        return Charsets.UTF_8
    }

    // For other terminals, prefer the charset reported by the actual console.
    System.console()?.charset()?.let { return it }

    // Gradle's JavaExec can leave System.console() unavailable. Fall back to
    // the JDK's native encoding information when it is trustworthy.
    System.getProperty("sun.stdout.encoding")?.let { name ->
        runCatching { return Charset.forName(name) }
    }
    System.getProperty("native.encoding")?.let { name ->
        runCatching { return Charset.forName(name) }
    }

    return Charsets.UTF_8
}
