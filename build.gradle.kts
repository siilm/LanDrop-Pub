plugins {
    kotlin("jvm") version "2.1.0" apply false
    kotlin("plugin.serialization") version "2.1.0" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}