pluginManagement {
    plugins {
        id("com.gradleup.shadow") version "9.0.0-beta8"
    }
}

rootProject.name = "landrop"

include(":shared")
include(":core")
include(":gateway")
include(":proto")