plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("com.example.myandroidime.repl.MainKt") }
tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
}
dependencies { implementation(project(":ime-core")) }
