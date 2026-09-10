// AGP 9+ has built-in Kotlin support — the `org.jetbrains.kotlin.android`
// plugin must NOT be applied, it fails the build.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aridemo"

    // 36 because the SDK AAR requires it: leviathan builds the library against
    // 36, so its aar-metadata carries minCompileSdk=36 and AGP fails the build
    // of any consumer compiled lower. targetSdk stays where it is — that is a
    // separate opt-in into new runtime behaviour, and this change is not it.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aridemo"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    // AriToolsAssetTest builds AriToolService to read its registry. No handler
    // runs, so no Android context is needed — but the stubbed android.jar must
    // return defaults rather than throw. Any project that unit-tests a subclass
    // of AriToolProviderService needs this; the SDK cannot set it for you.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// `assets/ari_tools.json` is generated from AriToolService's registry, never
// written by hand. The unit test does both jobs, so there is one mechanism:
//
//   regenerate:  ./gradlew :app:testDebugUnitTest -Pari.writeToolsAsset
//   check only:  ./gradlew :app:testDebugUnitTest
//
// The check runs in every build and fails when the committed asset stops
// matching the code, so a tool added in code and forgotten in the asset is a
// red test rather than a tool Ari never offers.
val ariToolsAssets = layout.projectDirectory.dir("src/main/assets")

// CircleDeeplinkTest reads the manifest to check the `show_circle` intent filter
// still matches the uri that tool declares. Nothing at runtime reports that pair
// drifting apart: Ari fires ACTION_VIEW, and a filter that no longer matches means
// Android drops the intent with no error reaching this app.
val ariAppManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")

tasks.withType<Test>().configureEach {
    // The test reads the asset through this absolute path, so Gradle cannot infer
    // it. Without declaring it, a hand-edit of the asset leaves the test task
    // UP-TO-DATE and the drift check never runs — which is exactly the edit it
    // exists to catch.
    inputs.dir(ariToolsAssets)
        .withPropertyName("ariToolsAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Same reasoning for the manifest: without this, removing the intent filter
    // leaves the test task UP-TO-DATE and the check that would catch it is skipped.
    inputs.file(ariAppManifest)
        .withPropertyName("ariAppManifest")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The tests must not guess where the module is: a unit test's working
    // directory is not the module directory under every runner.
    systemProperty("ari.tools.assetsDir", ariToolsAssets.asFile.absolutePath)
    systemProperty("ari.app.manifest", ariAppManifest.asFile.absolutePath)
    systemProperty(
        "ari.tools.write",
        providers.gradleProperty("ari.writeToolsAsset").isPresent.toString(),
    )
}

dependencies {
    // Resolved from sdk-repo/, the Maven repository committed in this repo.
    // The SDK's own dependencies — kotlin-stdlib, kotlinx-serialization-json and
    // the two coroutines artifacts — come from its POM, so nothing here lists them.
    implementation("com.ari_os:ari-tool-sdk:0.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")

    // The android unit-test jar stubs org.json and returns defaults, so reading
    // the generated asset back needs the real implementation. On a device
    // org.json ships in the framework, so it is a test dependency only and
    // never reaches the APK.
    testImplementation("org.json:json:20250517")

    // AriToolHandlerTest drives real tool calls, and every handler runs on the
    // main dispatcher. A JVM test has to supply one, so it needs setMain and a
    // test dispatcher. Version matched to the coroutines version the SDK's POM
    // brings in, so the test dispatcher and the runtime agree.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}
