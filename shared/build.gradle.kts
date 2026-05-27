plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val coroutines_version: String by project
val logback_version: String by project

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")

    implementation(project(":proto"))
}