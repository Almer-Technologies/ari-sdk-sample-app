pluginManagement {
    repositories {
        // The Ari Gradle plugin (com.ari-os.ari-tools) is on Maven Central, and
        // pluginManagement resolves from its own list — so mavenCentral() is named
        // again under dependencyResolutionManagement, and neither is redundant.
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()

        // com.ari-os:ari-tool-sdk. Its POM declares kotlin-stdlib,
        // kotlinx-serialization-json and the two coroutines artifacts, so nothing
        // has to list them by hand.
        mavenCentral()
    }
}

rootProject.name = "ari-tool-sample"

include(":app")
