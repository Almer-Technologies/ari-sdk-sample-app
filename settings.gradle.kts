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

// Vendored copies of leviathan's libs/ari-tool-protocol and libs/ari-tool-sdk.
// See ari-tool-sdk/VENDORED_FROM.txt. Upstream splits the frozen AIDL wire
// contract from the SDK built on it, and publishes them as two artifacts, so
// the vendored copy keeps the same two modules.
//
// Replace both with a published dependency once the RealWear Maven repository
// exists: depend on com.ari_os.ari:ari-tool-sdk, which brings the protocol
// with it via `api` scope.
include(":ari-tool-protocol")
include(":ari-tool-sdk")
