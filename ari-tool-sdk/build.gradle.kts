// Standalone build for the VENDORED copy of leviathan's libs/ari-tool-sdk.
//
// The real module uses leviathan's convention plugins; this reproduces the
// parts that matter (AIDL, kotlin source dir, SDK levels) with plain AGP so a
// partner can build the sample without the monorepo.
//
// Do not edit the sources under src/ — see ../README.md.
// AGP 9+ has built-in Kotlin support — the `org.jetbrains.kotlin.android`
// plugin must NOT be applied, it fails the build.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ari_os.ari.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        // Off by default under AGP 8+. The SDK is AIDL-based, so this is required.
        aidl = true
    }

    // leviathan's convention plugin registers src/main/kotlin; plain AGP does not.
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
