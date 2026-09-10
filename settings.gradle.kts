pluginManagement {
    repositories {
        // The Ari Gradle plugin ships beside the SDK AAR in sdk-repo/, and
        // pluginManagement resolves from its own list — so the folder is named
        // again under dependencyResolutionManagement, and neither is redundant.
        maven { url = uri(settingsDir.resolve("sdk-repo")) }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // The SDK ships as an AAR in this repository, in ordinary Maven layout —
        // see sdk-repo/BUILT_FROM.txt. A maven repository rather than flatDir
        // because a bare AAR carries no dependency metadata: flatDir would compile
        // and then fail at runtime with NoClassDefFoundError for kotlin-stdlib,
        // kotlinx-serialization-json and the two coroutines artifacts. The POM
        // beside the AAR declares all four, so nothing has to list them by hand.
        maven {
            // settingsDir, not a bare relative path: a settings script's own
            // directory is the only stable anchor, and the build has to work
            // wherever a partner unpacked the archive.
            url = uri(settingsDir.resolve("sdk-repo"))
        }
    }
}

rootProject.name = "ari-tool-sample"

include(":app")
