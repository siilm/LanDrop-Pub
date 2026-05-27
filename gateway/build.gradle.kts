plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow")
}

dependencies {
    implementation(project(":proto"))
    implementation(project(":shared"))
    implementation(project(":core"))

    // Ktor Netty server
    implementation("io.ktor:ktor-server-core:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-netty:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-websockets:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-content-negotiation:${property("ktor_version")}")
    implementation("io.ktor:ktor-server-auth:${property("ktor_version")}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${property("ktor_version")}")

    // kotlinx-serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${property("serialization_version")}")

    // kotlinx-coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${property("coroutines_version")}")

    // Koin DI
    implementation("io.insert-koin:koin-core:${property("koin_version")}")

    // Logging
    implementation("ch.qos.logback:logback-classic:${property("logback_version")}")

    // Exposed (CLI commands direct DB access)
    val exposed_version: String by project
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")

    // JWT (v3 认证体系: gateway 本地验 JWT，不调 core)
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.5")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // ── 测试依赖 ────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:${property("ktor_version")}")
    testImplementation("io.ktor:ktor-server-websockets:${property("ktor_version")}")
    testImplementation("io.ktor:ktor-client-websockets:${property("ktor_version")}")
    testImplementation("io.ktor:ktor-client-cio:${property("ktor_version")}")
    testImplementation("io.ktor:ktor-client-content-negotiation:${property("ktor_version")}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${property("coroutines_version")}")
}

application {
    mainClass.set("ink.siilm.gateway.ApplicationKt")
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    // 排除签名文件，避免 "Invalid signature file digest" 错误
    exclude("META-INF/*.SF")
    exclude("META-INF/*.DSA")
    exclude("META-INF/*.RSA")
    // 排除重复的许可文件
    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
}