plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

val coroutines_version: String by project
val exposed_version: String by project
val koin_version: String by project
val logback_version: String by project
val h2_version: String by project
val sqlite_version: String by project

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version")

    // 持久化
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("com.h2database:h2:$h2_version")
    implementation("org.xerial:sqlite-jdbc:$sqlite_version")

    // MySQL 生产数据库
    implementation("com.mysql:mysql-connector-j:9.2.0")
    // HikariCP 连接池
    implementation("com.zaxxer:HikariCP:6.2.1")

    // JWT (v3 认证体系: core 签发 JWT，gateway 验 JWT)
    implementation("io.jsonwebtoken:jjwt-api:0.12.5")
    implementation("io.jsonwebtoken:jjwt-impl:0.12.5")
    implementation("io.jsonwebtoken:jjwt-jackson:0.12.5")

    // DI
    implementation("io.insert-koin:koin-core:$koin_version")

    // 日志
    implementation("ch.qos.logback:logback-classic:$logback_version")

    // 内部模块（proto 模块提供 protobuf 生成的类，shared 提供 CoreBridge）
    implementation(project(":shared"))
    implementation(project(":proto"))

    // ── 测试依赖 ────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

// 注意：core 的 build.gradle.kts 中**没有** ktor-server、netty 等网络依赖
// protobuf 编译由 :proto 模块独自完成，core 通过 project(":proto") 获取生成的类
