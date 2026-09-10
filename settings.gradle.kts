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
include(":ari-tool-sdk")
