// AGP 9+ has built-in Kotlin support — the `org.jetbrains.kotlin.android`
// plugin must NOT be applied, it fails the build.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aridemo"
    compileSdk = 35

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
    // return defaults rather than throw. Documented in the SDK's README.
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

tasks.withType<Test>().configureEach {
    // The test reads the asset through this absolute path, so Gradle cannot infer
    // it. Without declaring it, a hand-edit of the asset leaves the test task
    // UP-TO-DATE and the drift check never runs — which is exactly the edit it
    // exists to catch.
    inputs.dir(ariToolsAssets)
        .withPropertyName("ariToolsAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The test must not guess where the assets folder is: a unit test's working
    // directory is not the module directory under every runner.
    systemProperty("ari.tools.assetsDir", ariToolsAssets.asFile.absolutePath)
    systemProperty(
        "ari.tools.write",
        providers.gradleProperty("ari.writeToolsAsset").isPresent.toString(),
    )
}

dependencies {
    implementation(project(":ari-tool-sdk"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")

    // The android unit-test jar stubs org.json and returns defaults, so reading
    // the generated asset back needs the real implementation. Same reason the
    // SDK module declares it. On a device org.json ships in the framework, so
    // neither this nor the SDK adds it to the APK.
    testImplementation("org.json:json:20250517")

    // AriToolDeclarationFile is the type the Ari host decodes the asset with,
    // and the SDK holds kotlinx.serialization as `implementation`, so the
    // round-trip check needs its own copy on the test classpath only.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
}
