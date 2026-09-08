// Standalone build for the VENDORED copy of leviathan's libs/ari-tool-protocol.
//
// This file is the ONLY part of the module that is not upstream's, because
// upstream builds it with leviathan's convention plugins. It reproduces what
// those plugins configure — SDK levels, the src/main/kotlin source dir, AIDL,
// default-returning unit tests — with plain AGP, so a partner can build the
// sample without the monorepo. Versions match leviathan's version catalog.
//
// Do not edit anything under src/ — see VENDORED_FROM.txt in ../ari-tool-sdk.
// AGP 9+ has built-in Kotlin support — the `org.jetbrains.kotlin.android`
// plugin must NOT be applied, it fails the build.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.library")
}

android {
    namespace = "com.ari_os.ari.sdk.protocol"
    compileSdk = 35

    defaultConfig {
        // A monorepo bump must not raise a partner's floor, so pin it here.
        minSdk = 30
    }

    buildFeatures {
        // Off by default under AGP 8+. This module IS the AIDL surface.
        aidl = true
    }

    // leviathan's convention plugin registers src/main/kotlin; plain AGP does not.
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    // What leviathan's AndroidLibraryConventionPlugin sets. AriToolsContract is
    // pure Kotlin, but the android.jar on the unit-test classpath is stubbed.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Ari and every partner app compile against this module, so it takes no
// dependency beyond the test runner.
dependencies {
    testImplementation("junit:junit:4.13.2")
}
