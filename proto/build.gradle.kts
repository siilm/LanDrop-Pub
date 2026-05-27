plugins {
    kotlin("jvm")
    id("com.google.protobuf")
    `java-library`
}

val protobuf_version: String by project

dependencies {
    api("com.google.protobuf:protobuf-java:$protobuf_version")
    api("com.google.protobuf:protobuf-kotlin:$protobuf_version")
}

sourceSets {
    main {
        proto {
            srcDir("src/main/protobuf")
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:$protobuf_version" }
    generateProtoTasks {
        all().forEach { it.builtins { create("kotlin") } }
    }
}