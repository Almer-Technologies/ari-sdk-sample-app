# Ari Tool Sample — numbered circles

A minimal Android app that exposes six tools to Ari.

**The Ari App Tools SDK is not in this repository.** What is here is the
sample's own source, under the Apache License 2.0. The SDK and its Gradle plugin
are proprietary RealWear software and come from the RealWear Developer Program
as a folder named `sdk-repo/` — drop that folder into the project root and
everything below works unchanged. See [NOTICE](NOTICE), and
[Build and install](#build-and-install) for what to do if you don't have it yet.

Build it, install it, and say:

> "Hey Ari, add a blue circle"

**The app does not need to be open** — Ari reads its declaration without launching it.

This repo is the worked example, not the SDK reference: the SDK's own
documentation is not in here, and this file covers what an integrating app does.

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
`confirm = true` and Ari asks the user before running them. The other four run
silently. The default is `false`, so a flag left off looks the same whether it
was decided or overlooked — hence a comment beside every declaration in
`DemoTools.kt` saying why, and a test pinning the whole set.

Circle numbers are **permanent**, so they can have gaps. Remove circle 2 of 4
and the rest stay 1, 3 and 4. That is what makes a batch of removals safe: the
model issues them in parallel, often before the first result comes back, and no
number has shifted. Every description says so, because without it the model
assumes numbers run 1..N and guesses wrong after a removal.

## Three lessons worth more than the code

**Prefer idempotent tools.** `set_circle_color` is idempotent — setting blue twice
looks identical to once. `add_circle` is not, so when the language model
occasionally regenerates a turn and repeats its tool call, you get a circle you
never asked for. Same model flakiness, wildly different consequence. Where a tool
must accumulate or destroy, say so in the description. `add_circle` declares no
`confirm`, so its description is the only thing standing between a regenerated
turn and a second circle. Setting `confirm` would not change that: a user who
has just asked for a circle says yes to being asked again.

**One tool call, one confirmation — so model the plural intent as a tool.**
Both removal tools declare `confirm = true`, so N calls means N prompts. This app
learned that on a headset: "remove all the green circles" produced two parallel
`remove_circle` calls, and the user had to say yes twice. The fix is not in the
confirmation layer, it is in the tool surface — `remove_circles_by_color` makes
the plural intent one call, and therefore one prompt. Look for the phrasing your
users will actually say ("all the", "every", "both") and ask whether your tools
can answer it in one call.

Dropping `confirm` would have silenced the second prompt too, and would have been
the wrong fix. The prompt is what a destructive tool owes the user; how often the
user is asked is a property of the tool surface, so that is where it belongs.
Both descriptions then carry the boundary between the two, because that text is
all the model has to choose with.

**Ari narrates before it knows.** The model often says "adding it" before reading
the tool result, so a failure can be spoken as a success. Return precise error
text — Ari reads it out — and don't rely on the user hearing the difference. The
same hazard has a quieter form on a *successful* call: asking
`remove_circles_by_color` for a colour no circle has removes nothing, which is a
success, so the result says `"removed": 0` and the description tells the model
what 0 means. Without that the natural narration is "removed the green ones"
when there never were any.

Where another of your tools genuinely repairs the failure, name it. `add_circle`
at the six-circle limit returns `fixWith = "remove_circle"`, and Ari offers that
tool to the user. Only name one
that really does fix it — Ari puts it in front of them, so a hopeful guess costs
a turn and some trust. The other two failures here name nothing, which is the
honest answer: no tool resolves a colour this app does not know, and "there's no
circle 7" already lists the circles that exist.

## How it works

Three things, and you write all three:

1. **`DemoTools.kt`** is an `object` implementing `AriToolDeclarations`. It
   declares every tool **in code, next to the function that runs it**. Note
   `values = CircleState.supportedNames()` on each `color` argument: the list
   the model picks from and the list the app resolves are one object, not two
   copies kept in step by hand.
2. **`AriToolService.kt`** returns `DemoTools.registry` from `tools()`, and
   nothing else. One registry is both what Ari is offered and what an
   invocation dispatches through, so the two cannot disagree.
3. **`app/src/main/AndroidManifest.xml`** publishes a service with the
   `com.ari_os.ari.action.TOOL_PROVIDER` action, protected by
   `com.ari_os.ari.permission.BIND_TOOL_PROVIDER` so only Ari can bind it. It
   holds **no pointer to the declaration** — a manifest-supplied path would
   still compile with a typo and then drop the provider at runtime, silently.

**`assets/ari_tools.json` is generated, and never committed.** The
`com.ari_os.ari-tools` Gradle plugin — an `id(...)` and an `ariTools { }` block
in `app/build.gradle.kts` — registers one task per variant that runs `DemoTools`
and writes the file under `build/`, wired in as a generated assets folder so it
always precedes `mergeAssets`.

That task runs the object in the build JVM, over your compiled classes, your
dependencies and the plain `android.jar`, whose every method throws `Stub!`. A
declaration may therefore **name** an Android type — `supportedNames()` reads a
map of Compose `Color`, a value class over a `ULong` — but must not **call** a
framework method. Keep those in the handlers; the generator names the cause.

At session start Ari opens that asset straight out of this app's installed APK —
no IPC, no app launch — and tells the cloud the tools exist. When you ask for
one, the call arrives over AIDL and the SDK dispatches it to the `handle { }`
block declared with that tool.

Every rule a declaration has to satisfy runs as `ariTools { }` builds the
registry, and the generator builds it at build time — so a malformed name, an
over-long description, a repeated tool or a template that leaves out a required
argument fails `:app:assembleDebug` rather than going quiet. What can still
vanish without a word is version skew: an Ari older than an argument type
refuses that value and drops the one tool using it. The file's
`declarationVersion` deliberately stays put when a type is added, so your other
tools keep loading.

`show_circle` is the exception. It is a **deeplink tool**: a `uri` and no
handler, so Ari never binds the service and this app's process is never started
for it. Its code is an `<intent-filter>` and `MainActivity`. Nothing at runtime
reports that pair drifting apart — if the filter stops matching, Android drops
the intent and the user just sees nothing happen — so `CircleDeeplink.kt` holds
the template as one constant both ends read, and `CircleDeeplinkTest` parses
`AndroidManifest.xml` off disk to check the filter still agrees. Three things
there are easy to get wrong, and the test comments say why: `CATEGORY_DEFAULT`
is required, `CATEGORY_BROWSABLE` is deliberately absent, and the activity is
`launchMode="singleTask"`.

## Build and install

**First, get `sdk-repo/`.** It is a Maven repository folder holding
`com.ari_os:ari-tool-sdk` and `com.ari_os:ari-tool-gradle-plugin`, and it is not
distributed in this repository — it comes from the RealWear Developer Program
(`info@realwear.com`), either on its own or inside the partner archive described
below. Put it in the project root, beside `settings.gradle.kts`. Without it the
build stops at the settings script and tells you the folder is missing; nothing
else here needs changing once it is in place.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build needs JDK 21 and an Android SDK with **API 36** — either an `sdk.dir`
in `local.properties` or `ANDROID_HOME` in the environment. No module declares a
Gradle toolchain, so the JDK Gradle runs on is the one that compiles the code.
Nothing is fetched over the network for the SDK or the plugin: both resolve out
of `sdk-repo/` on disk. To run every check:

```bash
./gradlew :app:testDebugUnitTest
```

`scripts/package-release.sh` builds the partner archive,
`build/dist/ari-tool-sample-<version>.zip`, taking the version from
`versionName` in `app/build.gradle.kts`, the source file list from git and
`sdk-repo/` off disk. That archive is one self-contained project: unpack it into
an empty directory and it builds, because the SDK is in there. Running the
script needs `sdk-repo/`, for the same reason building does.

`.github/workflows/build.yml` runs on `ubuntu-latest`. Two checks always run —
that no SDK binary and no generated `ari_tools.json` have been committed — and
the build and tests run only where `sdk-repo/` is present, which a clone of this
repository alone is not. Where they do run, the workflow reads
`assets/ari_tools.json` back out of the built APK and diffs it against what the
plugin wrote, proving the declaration reached the APK byte for byte. It cannot
detect an upstream SDK change breaking a partner: nothing here rebuilds the SDK,
so a green run proves the sample works against *the AAR that was supplied* — the
exact artifact you get.

On the device, the **Ari app must be installed** — it defines the permission
this app's service requires. Discovery runs when a voice session **starts**, so
installing this app mid-conversation leaves it invisible: restart the session.

## Pointing your own project at the SDK

Copy `sdk-repo/` in; `settings.gradle.kts` here is the template. The one thing
to copy exactly is that it names the folder **twice**: `pluginManagement` and
`dependencyResolutionManagement` resolve from separate lists, so a project with
only the second one compiles the SDK and cannot find the plugin. (This project
guards each entry with an `isDirectory` check and then fails with a sentence
naming the folder. That is only so a reader who cloned the sample without the
SDK gets told what is missing; your own project, which always has the folder,
does not need it.)

```kotlin
// settings.gradle.kts, in BOTH repository lists
maven { url = uri(settingsDir.resolve("sdk-repo")) }

// app/build.gradle.kts
plugins { id("com.ari_os.ari-tools") version "0.1.0" }
ariTools { declarations = "com.example.myapp.MyTools" }
dependencies { implementation("com.ari_os:ari-tool-sdk:0.1.0") }
```

What you import sits in two packages. `com.ari_os.ari.sdk.declaration` holds
what you declare tools with — `AriToolDeclarations`, `ariTools { }`,
`AriToolRegistry` — and `com.ari_os.ari.sdk` holds what runs them:
`AriToolProviderService`, `ToolArgs`, `AriToolResult`, `AriToolErrorCode`,
`AriToolAvailability`. There is a third, `com.ari_os.ari.sdk.protocol`, and most
of it is Ari's own half of the wire, marked so that using it is a compile error.
`AriToolsContract` lives there and is yours to read: it carries the limits your
declaration has to fit.

**The SDK brings its own dependencies — but only one of them to your compiler.**
`sdk-repo/` is a Maven repository, not a folder of loose files, so Gradle reads
the metadata beside the AAR and puts all four of `kotlin-stdlib`,
`kotlinx-serialization-json`, `kotlinx-coroutines-core` and
`kotlinx-coroutines-android` on the runtime classpath. That is why it is a
`maven { }` entry and not `flatDir`: a bare AAR carries no metadata, so a
`flatDir` project compiles and then dies with `NoClassDefFoundError`.

Only `kotlin-stdlib` reaches the **compile** classpath. The SDK keeps coroutines
out of its public API deliberately, so the other three are runtime scope: they
are in your APK, and naming a type from one of them in your own source is still
a compile error. Declare what you name, and pin it to the version the SDK
resolves — otherwise a transitive copy from somewhere else compiles your code
against one version while another runs it:

```kotlin
// only if your own code names a coroutines type, as CircleState does
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
```

Your app must compile against **API 36 or newer** (`compileSdk = 36`); the AAR
records that floor and AGP enforces it. Your `minSdk` and `targetSdk` are
unaffected — the SDK's own `minSdk` is 30.

Two more settings, needed only if you unit-test your `AriToolProviderService`
subclass on the JVM: `testOptions { unitTests.isReturnDefaultValues = true }`,
or the stubbed `android.jar` throws instead of returning defaults, and
`testImplementation("org.json:json:...")`, or a result comes back empty.

`BUILT_FROM.txt`, which comes inside `sdk-repo/`, records which build the AAR and
the plugin came from. When the RealWear Maven repository exists, `sdk-repo/`
becomes its URL and nothing else above changes.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Ari says it can't change colours | Discovery ran before this app was installed. Restart the session. |
| A tool you added in code never appears | The installed APK predates it, or discovery ran before you reinstalled. Rebuild, reinstall, restart the session. |
| The build says reading your declarations called an Android framework method | A tool declaration called into the framework. Only a `handle { }` may. |
| `show_circle` does nothing at all, with no error anywhere | The `<intent-filter>` does not match the uri Ari built. Android drops an unmatched intent silently and your app is never told. |
| A handler test throws about a missing main dispatcher | No `Dispatchers.setMain(...)` in `@Before`. Every handler runs on the main dispatcher and a JVM test has to supply one. |
| Your code won't compile against a coroutines type the APK clearly contains | The SDK depends on coroutines at runtime scope only. Declare `kotlinx-coroutines-core` yourself, at the SDK's version. |
| `Ari reads this. A tool provider declares tools and returns results instead.` | You reached into `com.ari_os.ari.sdk.protocol` for Ari's half of the wire. The opt-in marker is the SDK telling you there is a partner-facing way to do the same thing. |

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
`remove_circles_by_color` removing 2 circles and later 3, each in one call with
one confirmation, and `show_circle` opening `aridemo://circle/3` with no bind.

Still **not** exercised on hardware: `cancel()` and `AriToolAvailability.set`,
the oversize caps on results and arguments, declaration version skew, saying no
at a confirmation, an invalid enum value, the seventh-circle limit and the
`fixWith` it now returns, and Android 16 background-launch rules. The permission
gate is the one thing no JVM test here can cover either: the stubbed
`android.jar` makes it a no-op.

Two caveats on that run, and they have grown. It predates the generated asset.
It also predates the current AAR: the headset ran an earlier SDK build than the
one `sdk-repo/` now holds, and the newer one split the SDK's packages, renamed
its error codes and removed launch results altogether. `compileSdk` has moved
35 -> 36 as well. Read the run as evidence that the wire works end to end, not
that this tree has been on a device.

## Licence

The sample in this repository — everything except `sdk-repo/` — is licensed
under the Apache License 2.0. See [LICENSE](LICENSE).

The Ari App Tools SDK and its Gradle plugin are **not** covered by that licence.
They are proprietary RealWear software, supplied separately under a written
agreement, and they are not distributed here. See [NOTICE](NOTICE).
