// Versions pinned to the ones the Ari OS platform build used when it built the
// SDK AAR, so the sample compiles against the same Kotlin and AGP the SDK
// itself was built with.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
