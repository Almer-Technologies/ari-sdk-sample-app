pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ari-tool-sample"

include(":app")

// Vendored copy of leviathan's libs/ari-tool-sdk. See ari-tool-sdk/VENDORED_FROM.txt.
// Replace with a published dependency once artifact publishing exists.
include(":ari-tool-sdk")
