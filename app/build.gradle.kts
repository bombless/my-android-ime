plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.bombless.myandroidime"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        applicationId = "io.github.bombless.myandroidime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":ime-core"))
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.savedstate:savedstate:1.3.0")
}

// Generate the consonant-segmentation index on the build machine, then package it as an asset.
val consonantIndexDir = layout.buildDirectory.dir("generated/consonant-segmentation")
val consonantIndexFile = consonantIndexDir.map { it.file("consonant_index.tsv") }
val generateConsonantSegmentationIndex by tasks.registering(Exec::class) {
    dependsOn(rootProject.tasks.named("generateChineseDictionaryShards"))
    val generator = rootProject.file("tools/build_consonant_index.py")
    val dictionaries = rootProject.file("app/src/main/assets/dict-shards")
    inputs.file(generator)
    inputs.dir(dictionaries)
    outputs.file(consonantIndexFile)
    doFirst {
        consonantIndexDir.get().asFile.mkdirs()
        commandLine("python", generator.absolutePath, dictionaries.absolutePath, consonantIndexFile.get().asFile.absolutePath)
    }
}
android.sourceSets.getByName("main").assets.srcDir(consonantIndexDir)
tasks.named("preBuild").configure { dependsOn(generateConsonantSegmentationIndex) }
