// Versions pinned to the ones the Ari OS platform build used when it built the
// SDK AAR, so the sample compiles against the same Kotlin and AGP the SDK
// itself was built with.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false

    // Not one of the platform build's versions the header describes: this is
    // test tooling only, and changes nothing in the APK. 0.9.4 is the first
    // Kover that understands AGP 9, so this cannot drop below that.
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
}
