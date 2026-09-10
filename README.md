# Ari Tool Sample — numbered circles

A minimal Android app that exposes six capabilities to Ari. **It ships as two
archives — `ari-tool-sample-<version>.zip` and `ari-tool-sdk-<version>.zip` —
and it does not build until you have unpacked both.** Unpack the sample into an
empty directory, then unpack the SDK archive inside it, next to `app/`. The SDK
is the larger half by far, and separating it is what keeps the code you actually
read down to a few hundred lines.

Then install it, and say:

> "Hey Ari, add a blue circle"

**The app does not need to be open** — Ari reads its capabilities without launching it.

This repo is the worked example. [ari-tool-sdk/README.md](ari-tool-sdk/README.md)
is the reference for the SDK itself; where this file explains something the SDK
already documents, it points there instead of repeating it.

## The tools

| Tool | Args | Prompts? | Notes |
|---|---|---|---|
| `add_circle` | `color?` | no | Appends a circle, returns its number. Defaults red. |
| `remove_circle` | `number` | **yes** | One circle, by number. |
| `remove_circles_by_color` | `color` | **yes** | **Every circle of a colour, in one call.** One call, so one prompt. |
| `set_circle_color` | `color`, `number?` | no | Omit the number to recolour every circle. |
| `list_circles` | none | no | How Ari answers "what's on screen?" — it can't see the display. |
| `show_circle` | `number` | no | **A deeplink.** No handler: Ari opens `aridemo://circle/{number}` itself. |

"Prompts?" is the `confirm` flag, which the cloud reads: the two removals declare
`confirm = true` and Ari asks the user before running them. The other four
declare nothing and run silently. The default is `false`, so a flag left off
looks the same whether it was decided or overlooked — hence a comment beside
every declaration in `AriToolService.kt` saying why, including the four that
leave it off, and a test pinning the whole set.

Circle numbers are **permanent**, so they can have gaps. Remove circle 2 of 4
and the rest stay 1, 3 and 4. That is what makes a batch of removals safe: the
model issues them in parallel, often before the first result comes back, and no
number has shifted under any of them. Every description says so, because without
it the model assumes numbers run 1..N and guesses wrong after a removal.

## Three lessons worth more than the code

**Prefer idempotent tools.** `set_circle_color` is idempotent — setting blue twice
looks identical to once. `add_circle` is not, so when the language model
occasionally regenerates a turn and repeats its tool call, you get a circle you
never asked for. Same model flakiness, wildly different consequence. Where a tool
must accumulate or destroy, say so in the description (see `add_circle`).
`add_circle` declares no `confirm`, so nothing asks the user first — the
description is the only thing standing between a regenerated turn and a second
circle. Setting `confirm` would not change that: a user who has just asked for a
circle says yes to being asked again.

**One tool call, one confirmation — so model the plural intent as a tool.**
Both removal tools declare `confirm = true`, so Ari prompts before each of their
calls, and N calls means N prompts. This app learned that on a headset: "remove
all the green circles" produced two parallel `remove_circle` calls, and the user
had to say yes twice. The fix is not in the confirmation layer, it is in the tool
surface — `remove_circles_by_color` makes the plural intent one call, and
therefore one prompt. Look for the phrasing your users will actually say ("all
the", "every", "both") and ask whether your tools can answer it in one call.

Dropping `confirm` would have silenced the second prompt too, and would have been
the wrong fix. The prompt is what a destructive tool owes the user; the number of
times the user is asked is a property of the tool surface, so that is where it
belongs. Both descriptions then have to carry the boundary between the two,
because the descriptions are all the model has to choose with.

**Ari narrates before it knows.** The model often says "adding it" before reading
the tool result, so a failure can be spoken as a success. Return precise error
text — Ari reads it out — and don't rely on the user hearing the difference.
The same hazard has a quieter form on a *successful* call: asking
`remove_circles_by_color` for a colour no circle has removes nothing, which is a
success and not an error, so the result says `"removed": 0` and the description
tells the model what 0 means. Without that the natural narration is "removed the
green ones" when there never were any.

## How it works

Two files, and you write both:

1. **`AriToolService.kt`** extends `AriToolProviderService` and overrides
   `tools()`. It declares every tool **in code, next to the function that runs
   it**, and that is the entire integration. Note
   `values = CircleState.supportedNames()` on each `color` argument: the list the
   model picks from and the list the app resolves are the same object, not two
   copies kept in step by hand.
2. **`app/src/main/AndroidManifest.xml`** publishes a service with the
   `com.ari_os.ari.action.TOOL_PROVIDER` action, protected by
   `com.ari_os.ari.permission.BIND_TOOL_PROVIDER` so only Ari can bind it. It
   holds **no pointer to the declaration** — a manifest-supplied path would
   still compile with a typo and then drop the provider at runtime, silently.

**`app/src/main/assets/ari_tools.json` is generated, not written.**
`AriToolsAsset.writeTo` encodes it from the same registry `tools()` returns, and
a unit test fails the build if the committed file stops matching the code:

```bash
./gradlew :app:testDebugUnitTest -Pari.writeToolsAsset   # regenerate, then commit
./gradlew :app:testDebugUnitTest                         # check for drift
```

At session start Ari opens that asset straight out of this app's installed APK —
no IPC, no app launch — and tells the cloud the tools exist. When you ask for
one, the call arrives over AIDL and the SDK dispatches it to the `handle { }`
block declared with that tool. Anything invalid is dropped silently at runtime:
the tool simply never reaches the model, with no error in your app. That is what
the drift test protects you from.

`show_circle` is the exception. It is a **deeplink tool**: a `uri` and no
handler, so Ari never binds the service and this app's process is never started
for it. Its code is an `<intent-filter>` and `MainActivity`. Nothing at runtime
reports that pair drifting apart — if the filter stops matching, Android drops
the intent and the user just sees nothing happen — so `CircleDeeplink.kt` holds
the template as one constant both ends read, and `CircleDeeplinkTest` parses
`AndroidManifest.xml` off disk to check the filter still agrees with it. Three
things there are easy to get wrong, and the test comments say why:
`CATEGORY_DEFAULT` is required, `CATEGORY_BROWSABLE` is deliberately absent, and
the activity is `launchMode="singleTask"`.

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build needs JDK 21 and an Android SDK — either an `sdk.dir` in
`local.properties` or `ANDROID_HOME` in the environment. No module declares a
Gradle toolchain, so the JDK Gradle itself runs on is the one that compiles the
code. To run every check:

```bash
./gradlew :ari-tool-sdk:testDebugUnitTest :app:testDebugUnitTest
```

`scripts/package-release.sh` rebuilds the two archives into `build/dist/`,
taking the version from `versionName` in `app/build.gradle.kts` and the file
list from git.

`.github/workflows/build.yml` runs those plus `:app:assembleDebug` on
`ubuntu-latest`, then reads `assets/ari_tools.json` back out of the built APK and
diffs it against the committed source. It cannot detect an upstream SDK change
breaking a partner: the SDK here is a vendored copy, so a green run proves *this
copy* compiles and its tests pass.

On the device, the **Ari app must be installed** — it defines the permission
this app's service requires. Discovery runs when a voice session **starts**, so
installing this app mid-conversation leaves it invisible; end and restart the
conversation.

## Pointing your own project at the SDK

Copy `ari-tool-sdk/` into your project, add it to `settings.gradle.kts`, and
depend on it:

```kotlin
// settings.gradle.kts
include(":ari-tool-sdk")

// app/build.gradle.kts
dependencies {
    implementation(project(":ari-tool-sdk"))
}
```

Two build settings the SDK needs that a fresh module does not have:
`testOptions { unitTests.isReturnDefaultValues = true }`, or the stubbed
`android.jar` throws instead of returning defaults, and
`testImplementation("org.json:json:...")`, or a result comes back empty. Both are
in `app/build.gradle.kts` here, with comments.

`ari-tool-sdk/` is a **copy** of leviathan's `libs/ari-tool-sdk`, kept in this
repo only because there is nowhere to publish it to yet. Everything under `src/`
is byte-for-byte upstream, including upstream's own unit tests, which run here.
**Do not edit the copy** — change the real module in leviathan and re-copy.
`ari-tool-sdk/VENDORED_FROM.txt` records the commit and how to refresh. Once the
RealWear Maven repository exists, the swap is one dependency:

```kotlin
implementation("com.ari_os:ari-tool-sdk:<version>")
```

## Troubleshooting

| Symptom | Cause |
|---|---|
| Ari says it can't change colours | Discovery ran before this app was installed. Restart the session. |
| A tool you added in code never appears | The declaration asset was not regenerated. Run `./gradlew :app:testDebugUnitTest` — the drift test names the first line that differs. |
| `show_circle` does nothing at all, with no error anywhere | The `<intent-filter>` does not match the uri Ari built. Android drops an unmatched intent silently and your app is never told. |
| A handler test throws about a missing main dispatcher | No `Dispatchers.setMain(...)` in `@Before`. Every handler runs on the main dispatcher and a JVM test has to supply one. |

```bash
adb logcat -d | grep -iE "AppToolRegistry|tool provider|appTools"
```

A healthy session logs `Discovered 1 app tool provider(s)`.

## Verified on hardware

Verified on 2026-09-09 on a RealWear Arc 3 (model A31G, Android 13, firmware
`1.0.5-38-C.ARC3.G`) running Ari 2.5.4296 and the account app 1.2.4296, against
the SDK cloud deployment. Discovery took all of it: **1 provider, 6 tools
registered, 0 dropped**, surviving a reinstall, a force-stop of Ari, and
disabling and re-enabling the app. Every tool ran by voice, including
`remove_circles_by_color` removing 2 circles and later 3, each time in one call
with one confirmation, and `show_circle` opening `aridemo://circle/3` with no
service bind at all.

Still **not** exercised on hardware: `cancel()` and `setAvailable`, launch
results, the oversize caps on results and arguments, declaration version skew,
saying no at a confirmation, an invalid enum value, the seventh-circle limit,
Android 16 background-launch rules, `PendingIntent` immutability on API 30, and
the vendored SDK's own instrumented test (`AriToolDeclarationInstrumentedTest`,
for the ICU regex engine — it is compiled and packaged, never run). The
permission gate and `PendingIntent.isImmutable` cannot be covered by any JVM
test here: the stubbed `android.jar` makes the first a no-op and cannot report
the second.
