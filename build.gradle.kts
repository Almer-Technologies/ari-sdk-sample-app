// Versions pinned to what leviathan's gradle/libs.versions.toml built the SDK
// AAR with, so the sample compiles against the same Kotlin and AGP a partner
// would meet in the monorepo.
plugins {
    id("com.android.application") version "9.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
