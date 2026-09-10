# Ari Tool Sample — numbered circles

A minimal Android app that exposes six capabilities to Ari. Install it, then say:

> "Hey Ari, add a blue circle"

**The app does not need to be open** — Ari reads its capabilities without launching it.

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
declare nothing and run silently. Each is a decision, and each declaration says
why — see "Declaring tools" below.

Circle numbers are **permanent**, so they can have gaps. Remove circle 2 of 4
and the rest stay 1, 3 and 4. That is what makes a batch of removals safe: the
model issues them in parallel, often before the first result comes back, and no
number has shifted under any of them.

Permanent numbering is no longer what answers "remove the purple ones", though.
That is one call to `remove_circles_by_color`, and the reason it is a tool rather
than a batch is the second lesson below.

Every description in the declaration says the numbers are permanent and can
have gaps. That is deliberate — without it the model assumes numbers run 1..N
and guesses wrong after a removal.

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
belongs.

Both descriptions then have to carry the boundary, because the descriptions are
all the model has to choose between them. `remove_circle` says "Removes exactly
one circle, the one with this number", `remove_circles_by_color` says "Removes
every circle of one colour in a single call ... instead of calling remove_circle
once per match", and `remove_circle`'s old closing line — that removing several
circles in one go was safe — is gone, because it read as licence to do exactly
the thing this replaces.

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
   it**, and that is the entire integration.
2. **`app/src/main/AndroidManifest.xml`** publishes a service with the
   `com.ari_os.ari.action.TOOL_PROVIDER` action, protected by
   `com.ari_os.ari.permission.BIND_TOOL_PROVIDER` so only Ari can bind it. It
   holds **no pointer to the declaration** — a manifest-supplied path would
   still compile with a typo and then drop the provider at runtime, silently.

`show_circle` is the exception that proves what the service is for. It is a
**deeplink tool**: declared with a `uri` and no handler, so Ari never binds the
service and this app's process is never started for it. Its code is an
`<intent-filter>` and `MainActivity`. A partner whose tools are all deeplinks
writes no service at all — see "A tool that is only a link".

**`app/src/main/assets/ari_tools.json` is generated, not written.**
`AriToolsAsset.writeTo` encodes it from the same registry `tools()` returns, and
a unit test fails the build if the committed file stops matching the code. The
path is **fixed** (`AriToolsContract.DECLARATION_ASSET`), so there is nothing to
point at and nothing to misspell.

```bash
./gradlew :app:testDebugUnitTest -Pari.writeToolsAsset   # regenerate, then commit
./gradlew :app:testDebugUnitTest                         # check for drift
```

At session start Ari opens the asset straight out of this app's installed APK —
`getResourcesForApplication(...).assets.open("ari_tools.json")`, so no IPC and
no app launch — and tells the cloud the tools exist. When you ask for one, the
call arrives over AIDL and the SDK dispatches it to the `handle { }` block
declared with that tool.

## Declaring tools

One registry answers both questions Ari asks. This is the real thing, cut down
to one tool — see `app/src/main/java/com/example/aridemo/AriToolService.kt` for
all five:

```kotlin
class AriToolService : AriToolProviderService() {

    private val registry = ariTools {
        tool(
            name = "set_circle_color",
            description = "Changes the colour of one circle by its number, or of " +
                "every circle if no number is given.",
        ) {
            enum(
                "color",
                values = CircleState.supportedNames(),
                description = "The colour to change to.",
                required = true,
            )
            int(
                "number",
                description = "The circle's permanent number. Leave out to recolour all of them.",
            )
            handle { args ->
                setCircleColor(args.string("color"), args.intOrNull("number"))
            }
        }
    }

    override fun tools(): AriToolRegistry = registry
}
```

Note `values = CircleState.supportedNames()`. The allowed colours come from the
map that resolves them, so the list the model picks from and the list the app
understands are the same object. In the previous version of this sample those
were two hand-maintained copies, and a colour could be offered but unresolvable.

The registry rejects, as you build it, anything the contract forbids:

- tool and arg names must match `AriToolsContract.TOOL_NAME_REGEX`
  (`^[a-z][a-z0-9_]{0,31}$`);
- a tool needs a description, of at most 300 chars
  (`MAX_DESCRIPTION_LENGTH`). It is spent on every LLM turn, so keep it short
  and specific;
- no two tools may share a name;
- an `enum` declares at most 32 values (`MAX_ENUM_VALUES`), each of at most 64
  chars (`MAX_ENUM_VALUE_LENGTH`);
- a `tool()` needs exactly one `handle { }` block.

So a mistake fails your own build instead of being dropped from Ari's view.

One builder per argument type — `string`, `int`, `number`, `bool`, `enum` — and
only `enum` takes `values`, so a value list on any other type cannot be written.
Prefer `enum` where the value space is closed: it constrains the model to real
values instead of letting it invent `"cerulean"`.

**`confirm` decides whether the user is asked.** The cloud reads the flag on
`tool()` and on `deeplink()`. `true` means Ari asks the user before it runs the
tool and shows the argument values the call will send; `false` or absent means it
runs with no prompt. The default is `false`, so **a destructive tool that says
nothing gets no prompt.** Set it on anything destructive or hard to undo.

**Nothing verifies who declared it.** The declaration ships in your own APK, so
the flag protects the user only if you set it honestly. A provider that leaves
`false` on a tool that deletes data gets no prompt and the user sees no warning.
Treat it as a promise you are making, not a check Ari performs on your behalf.

Because the default is `false`, a flag left off looks identical whether it was
decided or overlooked. This app therefore says why in a comment beside every
declaration, including the four that leave it off, and pins the whole set in a
test (`the two removal tools are the only ones that declare confirm`) — dropping
`confirm` from a removal is otherwise a regression that shows up as nothing at
all in a diff.

That is also why `remove_circles_by_color` is a tool and not a flag: both
removals prompt, so the only way to ask the user once instead of twice is to make
it one call. Turning the flag off would have been the other way to silence the
second prompt, and the wrong one.

### A tool that is only a link

`show_circle` has **no handler**. Ari fills the `{number}` placeholder from the
argument and opens the result as `ACTION_VIEW` on this app's package. Nothing
binds the service, no IPC happens, and no code of this app's runs before the
screen appears:

```kotlin
deeplink(
    name = "show_circle",
    description = "Opens this app with the circle of this number highlighted. Use it " +
        "when the user asks to see or point out a circle rather than to change one.",
    uri = CircleDeeplink.TEMPLATE,          // "aridemo://circle/{number}"
) {
    int("number", description = "The circle's permanent number.", required = true)
}
```

Ari takes **only** the URI. No action, component, extras or flags come from the
declaration, so a tool can never ask Ari to send an arbitrary intent.

**A placeholder takes a constrained type by default.** `int`, `number`, `bool`
and `enum` may fill one. A plain `string` may not — the model writes its text
and nothing in the declaration limits what it writes, so a free-text value would
go straight into a URI another component then handles. The registry rejects it
as you build it:

```
tool 'show_circle': arg 'number' is free text, so the tool must name it in freeTextUriArgs to fill a uri placeholder
```

That costs this app nothing, because a circle's identity really is a whole
number. An argument that is free text *by nature* has an opt-in, covered below.

The registry also checks, as you build it, that every `{placeholder}` names an
arg of that tool, that every `required` arg appears in the template, and that
the template parses as a URI starting with a literal scheme — so no argument can
choose the scheme.

#### The free-text opt-in, and why this sample does not use it

Some values are free text by nature — a room id, a serial number printed on a
label. For those the SDK has `freeTextInUri`, a builder on `deeplink()` that
declares a `string` arg *and* lets it fill a placeholder, writing a
`freeTextUriArgs` list next to the `uri` in your asset. See "Opening a screen
with a deeplink" in [ari-tool-sdk/README.md](ari-tool-sdk/README.md).

**This sample deliberately ships no `freeTextInUri` tool.** Three reasons, and
they are the ones to weigh for your own app:

- **This app has no free-text value.** A circle is an `int` and its colour is an
  `enum`. Demonstrating the opt-in would mean inventing an argument no user
  story asks for, and a sample whose job is to show the real shape of an
  integration should not fabricate one.
- **The SDK says to prefer a handler when you have a service, and this app has
  one.** `freeTextInUri` is for when the deeplink *is* the whole integration.
  Five of the six tools here are handled over the binder, so a free-text
  deeplink would be the exact case the SDK's own README argues against.
- **The marker is worth having because it is rare.** Its point is that one grep
  for `freeTextInUri` finds every free-text deeplink you ship. A sample that
  ships one for demonstration teaches you that the hit is normal.

If free text is genuinely your case, a `tool { }` with a `handle { }` block is
still the better shape: your own code receives the phrase, validates it, and
opens your screen with `AriToolResult.launch(...)`, which puts your code between
the model's text and your app.

**If you do use `freeTextInUri`, be exact about what you are getting.** Ari
percent-encodes each value before it fills the template, so the value stays
inside the one URI component it fills: it cannot add a path segment, a query
parameter or a fragment, and it cannot change the scheme. **That is the whole of
it.** Percent-encoding stops *structural* injection and nothing else:

- **It is not sanitisation.** `..` survives it verbatim. So do `'`, `<`, `%00`,
  a leading `/`, and every other byte that means something to your code.
- **Your app decodes the value as its first act.** `Uri.getPathSegments()` and
  `getQueryParameter()` both hand you the *decoded* string, so the encoding is
  gone before your handler sees a single character of it. Every guarantee above
  expires on that line.
- **What you then do with it is entirely your problem.** Decoded and passed to
  `File(dir, value)` it is a path traversal — `../../databases` is four
  characters of nothing special to a URI. Concatenated into SQL it is an
  injection; bind a parameter. Handed to `WebView.loadUrl` it can be a
  `javascript:` URL. Handed to `Intent.parseUri` it can fabricate an intent
  aimed at your own exported components. Validate against an allow-list of what
  the value may be, not a deny-list of what you thought of.
- **`freeTextUriArgs` is a marker, not a wall.** You write your own declaration,
  so nothing stops a provider naming any argument in it. It stops the accidental
  case, where a `string` reaches a URI because nobody looked, and it records the
  deliberate one so it can be found later.

#### The half the declaration cannot reach

A `uri` in the asset is a promise your app answers the link. Nothing enforces
that at runtime: if the `<intent-filter>` does not match, Android drops the
intent, and **no error reaches your app** — the user just sees nothing happen.
So this sample keeps both ends on one constant, in `CircleDeeplink.kt`:

```kotlin
object CircleDeeplink {
    const val SCHEME = "aridemo"
    const val HOST = "circle"
    const val TEMPLATE = "$SCHEME://$HOST/{number}"

    private val PATTERN = Regex("$SCHEME://$HOST/(\\d{1,9})")

    fun circleNumber(uri: String?): Int? =
        uri?.let { link -> PATTERN.matchEntire(link)?.groupValues?.get(1)?.toInt() }
}
```

and the manifest declares the matching filter:

```xml
<activity
    android:name=".MainActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:host="circle" android:scheme="aridemo" />
    </intent-filter>
</activity>
```

Three things there are easy to get wrong:

- **`CATEGORY_DEFAULT` is required.** `startActivity` adds it to every implicit
  intent, so a filter without it matches nothing.
- **`CATEGORY_BROWSABLE` is deliberately absent.** Ari sets your package on the
  intent, so it does not need it — and adding it would let any web page fire
  `aridemo://circle/3` at your app too.
- **`launchMode="singleTask"`** so a second "show me circle 4" reaches the
  instance already on screen, at `onNewIntent`, instead of stacking another
  activity behind the first.

XML cannot read a Kotlin constant, so `CircleDeeplinkTest` parses
`AndroidManifest.xml` off disk and fails the build when its scheme and host stop
matching `CircleDeeplink`'s — the same drift check `ari_tools.json` gets.

**A substituted value is still input to validate.** The type rule bounds what
can arrive to a whole number. It does not check that the number names a circle
that exists, so `MainActivity` looks it up and says so when it does not:

> Ari asked for circle 9, which isn't on screen.

Finally, `presentsUi` is set for you — a deeplink tool returns no data — and if
Ari ever invokes a deeplink tool over the binder instead of opening the link,
the SDK answers `app_error` rather than running anything.

### What gets generated

```json
{
  "declarationVersion": 2,
  "protocolVersion": 1,
  "capabilities": [
    "cancel",
    "launch_result"
  ],
  "tools": [
    {
      "name": "set_circle_color",
      "description": "Changes the colour of one circle by its number, or of every circle if no number is given.",
      "args": [
        {
          "name": "color",
          "type": "enum",
          "values": ["red", "green", "blue"],
          "required": true,
          "description": "The colour to change to."
        }
      ]
    }
  ]
}
```

- `declarationVersion` — the declaration *format* version
  (`AriToolsContract.DECLARATION_VERSION`, currently `2`), not the AIDL version.
  The writer sets it. Leave it out and the **whole file** is rejected; it is not
  read as version `1`. Ari accepts its own version or older.
- `protocolVersion` — the AIDL surface this SDK speaks
  (`AriToolsContract.PROTOCOL_VERSION`). The writer sets it.
- `capabilities` — the optional parts of that surface the SDK implements. The
  writer lists every one.
- `label` — optional display name, from `ariTools(label = ...)`. Omitted here,
  so Ari falls back to the manifest label.
- There is **no `package` key**. Ari takes the package from the installed APK,
  so a declared one would only be a second source of truth. Earlier versions of
  this sample wrote one; it is gone.
- `freeTextUriArgs` — names the `string` args of one tool that may fill a
  `{placeholder}`, written by `freeTextInUri`. Absent here, and an absent key
  opts nothing in. See "The free-text opt-in" above.
- A key holding its default is left out, so `list_circles` has no `args` key at
  all. That is why only `show_circle` carries `presentsUi` and `uri`: they are
  the two keys that tell Ari to open a link rather than bind the service, and
  every other tool leaves both out.

Anything invalid is dropped silently at runtime: the tool simply never reaches
the model, with no error in your app. That is what the drift test protects you
from. Check Ari's logcat while integrating.

### Reading arguments

`handle { }` hands you a `ToolArgs`. Each declared type has a strict accessor
and an `OrNull` one:

| Declared type | Throws if unreadable | Returns `null` if unreadable |
|---|---|---|
| `string`, `enum` | `args.string("color")` | `args.stringOrNull("color")` |
| `int` | `args.int("number")` | `args.intOrNull("number")` |
| `number` | `args.number("ratio")` | `args.numberOrNull("ratio")` |
| `bool` | `args.bool("loud")` | `args.boolOrNull("loud")` |

Use the **strict** form for an arg you declared `required = true`. A missing or
wrong-typed value is then the model's mistake, and the SDK reports it as
`invalid_argument` naming the arg, so Ari can ask for better arguments. That is
why `remove_circle` calls `args.int("number")` and does not hand-roll a message.

Use the **`OrNull`** form for an optional arg, so you can say what a missing
value means for that tool. `set_circle_color` reads
`args.intOrNull("number")`, and absent means *every circle*.

An optional argument the user didn't mention is **absent**, and a JSON `null`
counts as absent too — `args.has("number")` is false for both. Falsy-but-meaningful
values (`0`, `false`, `""`) are preserved and reach you normally.

An accessor converts across types when the meaning is unambiguous (`"2"` reads
as `2`) and returns `null` rather than guessing when it would lose information
(`1.5` is not an `int`, `"yes"` is not a `bool`).

## Returning results

The payload is built with one accessor per JSON type, on `org.json`, which ships
in the framework:

```kotlin
AriToolResult.ok {                          // success, with a payload
    putInt("number", number)
    putString("color", color)
    putObject("circles") { putString("1", "red") }
}
AriToolResult.ok()                          // success, nothing to report
AriToolResult.error("I don't know that.")   // failure; Ari says this to the user
AriToolResult.error(AriToolErrorCode.INVALID_ARGUMENT, "There's no circle 7.")
```

There is no accessor for any other type, so nothing reaches the payload as an
accidental `toString()` — a `List`, a `Map` or a `LocalDate` is a **compile**
error until you say how you want it written. `list_circles` uses `putObject`
for that reason: the numbers stay a JSON object rather than prose the model has
to re-parse.

Prefer the coded `error(...)`: Ari maps an `AriToolErrorCode` to its own
translated text and uses it to decide whether a retry can help. This app returns
`UNAVAILABLE` when the circle limit is reached (removing one makes a retry work)
and `INVALID_ARGUMENT` for a number that does not exist (the model can pick a
better one).

Error text is spoken to the user. Write a short explanation, not a stack trace.

## Testing your handlers

`invokeToolInTest` runs one tool call from a JVM unit test and hands back the
result Ari would read. The call enters your service through the same binder Ari
calls, so one test covers the argument parsing, the handler and the error
envelope — there is no separate test path to prove.

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class AriToolHandlerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CircleState.reset()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `add_circle reports the number, the colour and the new total`() {
        val result = AriToolService().invokeToolInTest("add_circle", """{"color":"blue"}""")

        val payload = (result as AriToolResult.Ok).payload
        assertEquals(2, payload.int("number"))
        assertEquals("blue", payload.string("color"))
    }

    @Test
    fun `remove_circle without its required argument names the argument`() {
        val result = AriToolService().invokeToolInTest("remove_circle", "{}")

        val failure = result as AriToolResult.Failure
        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertEquals("arg 'number' is missing", failure.message)
    }
}
```

`app/src/test/java/com/example/aridemo/AriToolHandlerTest.kt` is the full set —
sixteen tests over all five handled tools, including the circle limit, the number
gap a removal leaves, a colour outside the declared `enum`, an undeclared tool
name, and both edges of the bulk removal: a colour that matches nothing (a
success reporting `"removed": 0`) and `gray` reaching a circle added as `grey`,
because the palette holds two names for one colour and the user cannot hear which
one anybody used.

Your test module needs three things, and each missing piece fails its own way:

```kotlin
android {
    testOptions { unitTests.isReturnDefaultValues = true }   // or the stubs throw
}

dependencies {
    testImplementation("org.json:json:20250517")                        // or no result at all
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")  // or setMain is missing
}
```

Five things worth knowing before you copy this:

- **The `@OptIn` is not decoration.** `Dispatchers.setMain`,
  `UnconfinedTestDispatcher` and `Dispatchers.resetMain` are all marked
  `ExperimentalCoroutinesApi`. Without the annotation you get three warnings,
  not a failed build — unless your project sets `allWarningsAsErrors`, and then
  it is a failed build.
- **`Ok.payload` reads flat values only.** It is a `ToolArgs`, so
  `payload.string("circles")` **throws** on `list_circles`'s nested object. Read
  a nested payload through `org.json`, off `payload.toString()`.
- **A launch tool cannot be tested here.** `AriToolResult.launch(...)` needs
  `PendingIntent.isImmutable`, which the android unit-test jar cannot report, so
  it comes back as an `unavailable` failure. This app declares no launch tool;
  if yours does, cover it on a device.
- **The permission gate cannot be tested here either.** `enforceCallingPermission`
  is a no-op under that same jar, so no unit test can show it turning a caller
  away. Only a device proves the service is actually closed to other apps.
- **A deeplink tool has no handler to test.** Invoking `show_circle` through the
  binder reports `app_error` — "this tool is a deeplink, so the app runs no code
  for it" — which is worth pinning, because it is what Ari taking the wrong path
  looks like. What the link actually opens is not reachable from a JVM; see
  `CircleDeeplinkTest` for how far a static check gets.


## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The build needs JDK 21 and an Android SDK — either an `sdk.dir` in
`local.properties` or `ANDROID_HOME` in the environment. No module declares a
Gradle toolchain, so the JDK Gradle itself runs on is the one that compiles the
code. To run every check, including the declaration drift test:

```bash
./gradlew :ari-tool-sdk:testDebugUnitTest :app:testDebugUnitTest
```

## CI

`.github/workflows/build.yml` runs `:app:assembleDebug` and both modules'
test tasks on `ubuntu-latest`, then reads `assets/ari_tools.json` back out of
the built APK and diffs it against the committed source. A hosted runner is
enough: this build needs a JDK and an Android SDK and nothing else.

`:app:testDebugUnitTest` carries the two drift checks that would otherwise fail
silently: the declaration asset against `AriToolService`'s registry, and the
`show_circle` `<intent-filter>` against `CircleDeeplink`'s scheme and host. Both
manifest and asset are declared as task inputs, so editing either by hand
re-runs the tests rather than leaving them `UP-TO-DATE`.

**It does not detect upstream SDK changes breaking a partner.** The SDK here is
a vendored copy, so a green run proves *this copy* compiles and its tests pass.
Nothing in CI fetches the upstream modules, so upstream can move under this
sample and every run stays green until someone re-vendors by hand. That gap
closes when the SDK is a published artifact this repo resolves as a dependency,
and not before — see "Why the SDK is vendored here".

Requirements on the device:

- The **Ari app must be installed** — it defines the permission this app's
  service requires. Without it the service is unreachable.

There is no provider allowlist, on either side. Any installed app that declares
tools is discovered when a voice session **starts**, and the only per-tool gate
is the declared `confirm` flag: it defaults to false, and the cloud honours it
by asking the user before it runs that tool. Nothing verifies a provider's
signature in this experimental release.

Because discovery runs when a session starts, installing this app while Ari is
already in a conversation leaves it invisible. End and restart the
conversation.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Ari says it can't change colours | Discovery ran before this app was installed. Restart the session. |
| `SecurityException` on bind in logcat | The Ari app is missing `<uses-permission>` for its own `BIND_TOOL_PROVIDER`. Defining a permission does not grant it. |
| `Skipping <pkg>: no ari_tools.json asset` | The declaration is not at `app/src/main/assets/ari_tools.json`, or the filename is misspelt. |
| `Skipping <pkg>: no declaration version` | `declarationVersion` is missing from the top-level object. The whole file is rejected. |
| `Skipping <pkg>: failed to read or decode ari_tools.json` | Malformed JSON, or a field of the wrong type. |
| `tool '<name>' dropped: ...` | That one tool failed validation — bad name, missing description, `values` on a non-enum. The other tools still load. |
| A tool you added in code never appears | The declaration asset was not regenerated. Run `./gradlew :app:testDebugUnitTest` — the drift test names the first line that differs. |
| A deeplink tool does nothing at all, with no error anywhere | The `<intent-filter>` does not match the uri Ari built. Android drops an unmatched intent silently and your app is never told. Check the scheme, the host, and that `CATEGORY_DEFAULT` is on the filter. |
| A deeplink opens a second copy of the screen each time | The activity is not `launchMode="singleTask"`, so Ari's intent stacks a new instance instead of reaching `onNewIntent`. |
| `arg '<name>' is free text, so it cannot fill a uri placeholder` | A `string` arg in a `{placeholder}`. Only `int`, `number`, `bool` and `enum` may fill one — see "A tool that is only a link". |
| A handler test throws `tool '<name>' reported no result` | `org.json` is not on the test classpath, so the stubbed one returns defaults. Or the handler is still suspended — check the dispatcher. |
| A handler test throws about a missing main dispatcher | No `Dispatchers.setMain(...)` in `@Before`. Every handler runs on the main dispatcher and a JVM test has to supply one. |

Check discovery with:

```bash
adb logcat -d | grep -iE "AppToolRegistry|tool provider|appTools"
```

A healthy session logs `Discovered 1 app tool provider(s)`.

## Why the SDK is vendored here

`ari-tool-sdk/` is a **copy** of leviathan's `libs/ari-tool-sdk`, which holds
the frozen AIDL wire contract and the SDK built on it in one module. Upstream
used to split the two and merged them back together; this copy followed.

Everything under `src/`, plus `consumer-rules.pro`, `LICENSE` and `README.md`,
is byte-for-byte upstream — including upstream's own unit tests, which run here
and are what shows the copy is faithful rather than merely compiling.
`build.gradle.kts` is the **only** file that differs: upstream builds with
leviathan's convention plugins and version catalog, neither of which exists
here, so it is a plain-AGP rewrite of the same settings and the same dependency
versions. `ari-tool-sdk/VENDORED_FROM.txt` records the commit.

One exception to "run here": `ari-tool-sdk/src/androidTest` is copied but
**never executed**. Upstream added `AriToolDeclarationInstrumentedTest` because
Android compiles regexes with ICU and the host JVM does not, so the
declaration's URI checks need a real device engine to be meaningful.
`connectedDebugAndroidTest` needs a device or emulator, and neither this repo
nor its CI has one. The build and CI compile and package that source set
(`:ari-tool-sdk:assembleDebugAndroidTest`), so a copy that does not build fails
loudly, but running it is yours to do:

```bash
./gradlew :ari-tool-sdk:connectedDebugAndroidTest   # needs a device
```

It is copied rather than skipped so `src/` stays a byte-for-byte copy — that is
what keeps the next refresh a mechanical `cp` instead of a judgement call.

**Refresh by diffing the whole tree, not by trusting a list of changed files.**
Upstream's summary of what moved has been incomplete before:

```bash
diff -r -x build -x .gradle -x .kotlin <leviathan>/libs/ari-tool-sdk ./ari-tool-sdk
```

**Do not edit the copies.** Change the real modules in leviathan and re-copy.

It is still a copy only because there is nowhere to publish to yet. Upstream has
`maven-publish` wired up (group `com.ari_os`, version `0.1.0`), but the
convention plugin carries `TODO MOBILE-2340: add the RealWear remote repository
once it exists`, and `publishToMavenLocal` is the only working target. Once that
repository exists, the swap is one dependency: drop the `include(":ari-tool-sdk")`
line from `settings.gradle.kts` and replace the app's
`implementation(project(":ari-tool-sdk"))` with

```kotlin
implementation("com.ari_os:ari-tool-sdk:<version>")
```

## Verified on hardware

Verified on 2026-09-09 on a RealWear Arc 3 (model A31G, Android 13, firmware
`1.0.5-38-C.ARC3.G`) running Ari 2.5.4296 and the account app 1.2.4296, against
the SDK cloud deployment.

Discovery found this app and took all of it: **1 provider, 6 tools registered,
0 dropped**. The provider survived a reinstall of this app, a force-stop of
Ari, and disabling and re-enabling the app.

Every tool ran by voice:

| Tool | What happened |
|---|---|
| `add_circle` | Ran with no confirmation. |
| `list_circles` | Reported the circles on screen. |
| `set_circle_color` | Recoloured the circle it was given. |
| `remove_circle` | Asked once, then removed the circle. |
| `remove_circles_by_color` | Removed 2 circles, and on a later run 3, each time in **one call with one confirmation**. |
| `show_circle` | Fired by Ari itself as a deeplink. Ari opened `aridemo://circle/3` with no service bind at all, and the app came to the front. |

The provider process was killed and then re-bound with its state intact. Losing
the backend, and losing the tunnel, were both survivable.

Still **not** exercised on hardware:

- `cancel()` and `setAvailable`.
- Launch results (the `PendingIntent`) and the spoken line that goes with one.
- The oversize caps on results and on arguments.
- Declaration version skew.
- Saying no at a confirmation.
- An invalid enum value.
- The seventh-circle limit.
- `show_circle` by voice for a circle that does not exist.
- Android 16 background-launch rules.
- `PendingIntent` immutability on API 30.
- The vendored SDK's own instrumented test
  (`AriToolDeclarationInstrumentedTest`, for the ICU regex engine). It is
  compiled and packaged, and was not run in this pass.

The JVM checks still stand underneath all of that: the app builds, the
declaration ships inside the APK, it satisfies the real constants in
`AriToolsContract` (`AriToolsDeclarationContractTest`), and every tool returns
what it should when its handler is invoked through the binder
(`AriToolHandlerTest`).

The handler tests reach the service through the same binder Ari calls, but they
call it in-process. No real Binder transaction crosses, which is exactly why
the permission gate reads as a no-op there.
