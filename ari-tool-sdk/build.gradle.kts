// Standalone build for the VENDORED copy of leviathan's libs/ari-tool-sdk.
//
// This file is the ONLY part of the module that is not upstream's, because
// upstream builds it with leviathan's convention plugins. It reproduces what
// those plugins configure — SDK levels, the src/main/kotlin source dir,
// kotlinx.serialization, default-returning unit tests, the instrumentation
// runner — with plain AGP, so a partner can build the sample without the
// monorepo. Versions match leviathan's version catalog.
//
// Do not edit anything under src/ — see VENDORED_FROM.txt.
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
        // A monorepo bump must not raise a partner's floor, so pin it here.
        minSdk = 30

        // What leviathan's AndroidLibraryConventionPlugin sets. Without it the
        // vendored androidTest source set builds but has no runner to launch it.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        // Off by default under AGP 8+. This module holds the AIDL wire surface.
        aidl = true
    }

    // leviathan's convention plugin registers src/main/kotlin; plain AGP does not.
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")

    // What leviathan's AndroidLibraryConventionPlugin sets. AriToolProviderService
    // is built directly in a unit test, so the stubbed android.jar must return
    // defaults rather than throw.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Third-party apps compile against this module, so no internal leviathan
// module may become a dependency.
dependencies {
    // The declaration models are @Serializable, and AriToolsAsset encodes them.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // `implementation`, not `api`: no kotlinx-coroutines type may appear in the
    // public surface. Overriding a handler needs only the stdlib `Continuation`.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Supplies the Android main dispatcher used by the service scope.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    // The android unit-test jar stubs org.json and returns defaults, so the
    // real implementation has to come from the test classpath.
    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")

    // PendingIntent has no public constructor, and the android unit-test jar
    // builds none, so a launch test needs a mocked instance.
    testImplementation("io.mockk:mockk:1.13.17")

    // Android compiles a regex with ICU, so the declaration checks need a test
    // that runs on a device. Test-only: neither reaches a partner's classpath.
    //
    // NOTHING IN THIS REPO RUNS THESE. connectedDebugAndroidTest needs a
    // connected device or emulator, and CI has neither. They are vendored so
    // that src/ stays a byte-for-byte copy of upstream, and so a partner with a
    // headset can run ./gradlew :ari-tool-sdk:connectedDebugAndroidTest. The
    // build only compiles them, via :ari-tool-sdk:assembleDebugAndroidTest.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
