pluginManagement {
    repositories {
        // The Ari Gradle plugin ships beside the SDK AAR in sdk-repo/, and
        // pluginManagement resolves from its own list — so the folder is named
        // again under dependencyResolutionManagement, and neither is redundant.
        //
        // sdk-repo/ is NOT part of this repository: it is proprietary RealWear
        // software, supplied separately (see NOTICE). Registered only when it is
        // present, so the repository list never advertises a directory that is
        // not there; the check below reports the absence in one sentence.
        val sdkRepo = settingsDir.resolve("sdk-repo")
        if (sdkRepo.isDirectory) {
            maven { url = uri(sdkRepo) }
        }

        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()

        // The SDK ships as an AAR in ordinary Maven layout — see
        // sdk-repo/BUILT_FROM.txt, which comes with the folder. A maven
        // repository rather than flatDir because a bare AAR carries no
        // dependency metadata: flatDir would compile and then fail at runtime
        // with NoClassDefFoundError for kotlin-stdlib, kotlinx-serialization-json
        // and the two coroutines artifacts. The POM beside the AAR declares all
        // four, so nothing has to list them by hand.
        //
        // settingsDir, not a bare relative path: a settings script's own
        // directory is the only stable anchor, and the build has to work
        // wherever a partner unpacked the archive.
        val sdkRepo = settingsDir.resolve("sdk-repo")
        if (sdkRepo.isDirectory) {
            maven { url = uri(sdkRepo) }
        }
    }
}

// Everything above resolves out of sdk-repo/, and this repository does not
// contain it. Say so here, while the two repository lists are still the subject,
// rather than letting the build die later on
//
//     Plugin [id: 'com.ari_os.ari-tools', version: '0.1.0'] was not found
//
// which reads like a broken build script rather than a missing download.
require(settingsDir.resolve("sdk-repo").isDirectory) {
    """

    sdk-repo/ is missing, so the Ari App Tools SDK cannot be resolved.

    This repository holds the sample's source only. The SDK and its Gradle
    plugin —

        com.ari_os:ari-tool-sdk
        com.ari_os:ari-tool-gradle-plugin

    — are proprietary RealWear software and are not distributed here. They
    arrive as a folder named sdk-repo/, a Maven repository in ordinary layout,
    from the RealWear Developer Program. Contact info@realwear.com to obtain it.

    Put the folder at

        ${settingsDir.resolve("sdk-repo")}

    and run the build again. See README.md, "Build and install".

    """.trimIndent()
}

rootProject.name = "ari-tool-sample"

include(":app")
