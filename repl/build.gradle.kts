plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("io.github.bombless.myandroidime.repl.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
}
dependencies { implementation(project(":ime-core")) }
