plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

tasks.register("prepareChineseDictionary") {
    val source = layout.projectDirectory.dir(".vendor/rime-ice/cn_dicts")
    val target = layout.projectDirectory.dir("app/src/main/assets/dict")
    inputs.dir(source)
    outputs.dir(target)
    doLast {
        target.asFile.mkdirs()
        listOf(
            "base.dict.yaml",
            "ext.dict.yaml",
            "tencent.dict.yaml",
            "8105.dict.yaml",
            "41448.dict.yaml",
        ).forEach { name ->
            val src = source.file(name).asFile
            if (src.exists()) {
                src.copyTo(target.file(name).asFile, overwrite = true)
            } else {
                check(target.file(name).asFile.exists()) {
                    "Missing dictionary $name; either keep the committed assets or clone iDvel/rime-ice into .vendor/rime-ice"
                }
            }
        }
    }
}

tasks.register<Exec>("generateChineseDictionaryShards") {
    val source = layout.projectDirectory.dir(".vendor/rime-ice/cn_dicts")
    val output = layout.projectDirectory.dir("app/src/main/assets/dict-shards")
    val script = layout.projectDirectory.file("tools/split_rime_dict.py")
    dependsOn("prepareChineseDictionary")
    inputs.dir(source)
    inputs.file(script)
    outputs.dir(output)
    doFirst {
        delete(output)
    }
    commandLine("python", script.asFile.absolutePath, source.asFile.absolutePath, output.asFile.absolutePath)
}

project(":app") {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(rootProject.tasks.named("generateChineseDictionaryShards"))
    }
}