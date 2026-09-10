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

// Vendored copy of leviathan's libs/ari-tool-sdk, which holds the AIDL wire
// contract and the SDK built on it in one module. See
// ari-tool-sdk/VENDORED_FROM.txt.
//
// Replace it with a published dependency once the RealWear Maven repository
// exists: com.ari_os:ari-tool-sdk.
//
// The sample ships as two archives (scripts/package-release.sh), so this
// directory is absent for anyone who unpacked only one of them. Gradle's own
// answer to that is "Project with path ':ari-tool-sdk' could not be found",
// which names neither the archive nor the fix.
if (!settingsDir.resolve("ari-tool-sdk").isDirectory) {
    throw GradleException(
        """
        ari-tool-sdk/ is missing, so this project cannot be configured.

        The sample ships as two archives, and both unpack into this directory:

            ari-tool-sample-<version>.zip   this project
            ari-tool-sdk-<version>.zip      the SDK it builds against

        Unpack the SDK archive in this directory, next to app/, and build again:

            unzip ari-tool-sdk-<version>.zip
        """.trimIndent(),
    )
}

include(":ari-tool-sdk")
