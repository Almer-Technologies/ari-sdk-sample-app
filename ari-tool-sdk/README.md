## Purpose

Public SDK for exposing app capabilities ("tools") to Ari. A provider app
declares its tools in code, ships the `assets/ari_tools.json` that declaration
writes, publishes a service with `AriToolsContract.ACTION_TOOL_PROVIDER`, and
extends `AriToolProviderService`.

Ari discovers providers at session connect, declares them to the cloud, and
routes LLM tool calls to your app over AIDL.

**This module is third-party API surface.** It must not depend on internal
leviathan modules.

## Modules

The SDK ships as two artifacts under the group `com.ari_os.ari`.

| Artifact | Holds | Depends on |
|---|---|---|
| `ari-tool-protocol` | The two AIDL interfaces and `AriToolsContract`. The frozen wire surface. | nothing |
| `ari-tool-sdk` | `AriToolProviderService`, the `ariTools { }` registry, `AriToolsAsset`, `AriTools`, `AriToolResult`, `AriToolErrorCode`, `AriToolCall`, `ToolArgs`, `invokeToolInTest`, the declaration models. | `ari-tool-protocol`, with `api` scope |

Depend on `ari-tool-sdk` only. It exposes the contract with `api` scope, so the
protocol arrives with it:

```kotlin
implementation("com.ari_os.ari:ari-tool-sdk:<version>")
```

The SDK types use `org.json`, which ships in the framework, so the SDK adds
nothing to your compile classpath.

## Integrating

### 1. Declare your tools — in code

Your service overrides one member. It returns every tool you expose, each with
the code that runs it:

```kotlin
class AriToolService : AriToolProviderService() {

    override fun tools() = ariTools(label = "Ari Demo") {
        tool("set_circle_color", "Sets the colour of the circle shown in the app.") {
            enum(
                "color",
                values = CircleColor.entries.map { color -> color.wireName },
                description = "The colour to change the circle to.",
                required = true,
            )
            handle { args -> setCircleColor(args.string("color")) }
        }

        deeplink(
            "open_work_order",
            "Opens the work order with this number.",
            uri = "hpfield://order/{number}",
        ) {
            int("number", "The work order number, as printed on the job sheet.", required = true)
        }
    }
}
```

One registry answers both questions. Ari reads the declarations from it, and the
SDK dispatches every call to the handler declared next to them. So a tool and
the code that runs it cannot drift apart, and a list of allowed values can come
from your own enum instead of a copy you keep in step by hand.

`tool { }` is a tool Ari runs by binding your service. It needs exactly one
`handle { }` block. `deeplink()` is a tool that is only a link: Ari opens it
itself, your process is never started, and no handler is allowed. A tool with no
handler, or with two, is rejected as you build the registry.

The rules the registry enforces:

- tool and arg names must match `^[a-z][a-z0-9_]{0,31}$`;
- a tool needs a description, of at most 300 chars. Ari sends it to the LLM on
  every turn, so keep it short and specific;
- no two tools may share a name;
- at most 8 tools per provider (`AriToolsContract.MAX_TOOLS_PER_PROVIDER`). Ari
  sends every name and description on every turn, so this caps the prompt budget
  one provider claims. The registry rejects a ninth tool, so you see it in your
  own build, not in Ari's log.

**`confirm` decides whether Ari asks the user.** The cloud reads the flag on
both builders. It defaults to `false`, and `false` means the tool runs with no
prompt. Set `true` on anything destructive or hard to undo.

Nothing checks who the declaring app is. Your declaration comes from your own
APK, so the flag protects the user only if you set it honestly. A provider that
leaves `false` on a destructive tool gets no prompt, and the user sees no
warning.

#### The argument builders

One builder per declared type. Each takes the name first, then the text the
model reads to pick a value:

| Builder | Declared `type` | Read the value with |
|---|---|---|
| `string("color", "The colour.")` | `string` | `args.string("color")` |
| `int("count")` | `int` | `args.int("count")` |
| `number("ratio")` | `number` | `args.number("ratio")` |
| `bool("loud")` | `bool` | `args.bool("loud")` |
| `enum("color", values = listOf("red", "green"))` | `enum` | `args.string("color")` |

`required = true` means Ari sends a value on every call. Only `enum` takes
`values`, because only `AriToolArg.EnumArg` holds them, so a value list on any
other type cannot be written.

`deeplink()` adds a sixth builder, `freeTextInUri`. It declares a `string` arg
and lets that arg fill a `{placeholder}`. See "Opening a screen with a
deeplink".

Every builder produces the same `AriToolDeclaration` and `AriToolArg` the SDK
already defines, so nothing about the wire format changes.

#### Opening a screen with a deeplink

If your app already has a deeplink, a tool can be that link and nothing else.
No service, no IPC, and your process is never started:

```kotlin
deeplink(
    "open_work_order",
    "Opens the work order with this number.",
    uri = "hpfield://order/{number}",
) {
    int("number", required = true)
}
```

Ari fills each `{arg_name}` placeholder from the declared arg of that name and
opens the result as `ACTION_VIEW` on your package. Only a URI is allowed. Ari
takes no action, component, extras or flags from your declaration, so a tool
can never ask Ari to send an arbitrary intent.

**A placeholder takes a constrained type by default.** `int`, `number`, `bool`
and `enum` may fill one. A `string` may not, because the model writes its text
and nothing in your declaration limits what it writes.

Some values are free text by nature. A room id is one. Declare such an argument
with `freeTextInUri` instead of `string`:

```kotlin
deeplink(
    "open_room",
    "Opens the room with this id.",
    uri = "aridemo://room/{room_id}",
) {
    freeTextInUri("room_id", "The room id, as printed on the door.", required = true)
}
```

That writes a `freeTextUriArgs` list next to the `uri` in your asset. The one
argument nothing bounds is then named in your own source and in your own file.

The registry checks the template as you build it:

- every `{placeholder}` must name an arg declared on the same tool;
- a `string` arg fills a `{placeholder}` only through `freeTextInUri`;
- every name in `freeTextUriArgs` must be a `string` arg the template fills;
- every `required` arg of the tool must appear in the template;
- the template must parse as a URI and start with a literal scheme, so no arg
  can choose the scheme.

An optional arg the template leaves out is allowed and never reaches your app.
A plain `string` arg is fine on a deeplink tool as long as the template leaves
it out, though it then reaches nothing and is better deleted.

**What `freeTextInUri` is, and is not.** You write your own declaration, so
nothing stops you naming any argument here. It is no wall against a provider
that means harm. It stops the accidental case, where a `string` reaches a URI
because nobody looked. And it marks the deliberate case, so one search over
your source finds every free-text deeplink you ship.

Ari percent-encodes each value before it fills the template, so a value stays
inside the one URI component it fills. It cannot add a path segment, a query
parameter or a fragment, and it cannot change the scheme. It is still text the
model chose, so your deeplink target must validate it like any other untrusted
input.

An Ari older than the key drops it, and then drops the tool, because it sees a
`string` in a placeholder. Your other tools still load.

**Prefer a handler when you have a service.** A search tool that takes a phrase
is better as a `tool { }` with a `handle { }` block: your own code receives the
text, validates it, and opens your screen with `AriToolResult.launch(...)`. Use
`freeTextInUri` when the deeplink is the whole integration.

`presentsUi` is set for you, because a deeplink tool returns no data.

An app whose tools are all deeplinks ships no service, so Ari finds it only by
the `<application>` meta-data flag. See "Publish the service".

### 2. Write the asset, and let a test keep it honest

Ari reads `assets/ari_tools.json` straight from your installed APK, before it
binds anything. Your app is never launched for it, and no permission or IPC is
involved. So the file has to be there, and it has to say what your code says.

Write it from the registry:

```kotlin
AriToolsAsset.writeTo(File("src/main/assets"), AriToolService().tools())
```

Then commit the file, and check it in a unit test:

```kotlin
class AriToolsAssetTest {

    @Test
    fun `the committed asset matches the tools the service declares`() {
        AriToolsAsset.requireMatches(File("src/main/assets"), AriToolService().tools())
    }
}
```

The check fails your build when the two differ, and its message names the first
line that changed. A tool you add in code and forget in the asset is then a red
test, not a tool Ari never offers.

The test builds your service and reads its registry. No handler runs, so no
Android context is needed. Your module needs one line for that:

```kotlin
android {
    testOptions { unitTests.isReturnDefaultValues = true }
}
```

"Testing a handler" adds the two dependencies a test needs to run a tool call.

`AriToolsAsset` takes the **assets folder**, not a file name, and always writes
`AriToolsContract.DECLARATION_ASSET` inside it. The path is fixed, so a typo
cannot silently drop your provider. `AriToolsAsset.encode(registry)` returns the
same text if you want to write it another way.

#### What the asset holds

```json
{
  "declarationVersion": 2,
  "protocolVersion": 1,
  "capabilities": [
    "cancel",
    "launch_result"
  ],
  "label": "Ari Demo",
  "tools": [
    {
      "name": "set_circle_color",
      "description": "Sets the colour of the circle shown in the app.",
      "args": [
        {
          "name": "color",
          "type": "enum",
          "values": [
            "red",
            "green",
            "blue"
          ],
          "required": true,
          "description": "The colour to change the circle to."
        }
      ]
    }
  ]
}
```

- `label` — optional display name for your app, from `ariTools(label = ...)`.
  Ari defaults to the app label from the manifest.
- `declarationVersion` is the declaration *format* version
  (`AriToolsContract.DECLARATION_VERSION`, currently `2`). The writer sets it.
  Ari accepts its own version or older, and rejects a newer one. A file that
  omits the field is rejected, not read as version `1`.
- `protocolVersion` is the AIDL surface your SDK speaks
  (`AriToolsContract.PROTOCOL_VERSION`, currently `1`). The writer sets it. Ari
  accepts your version when it is at or above its own floor.
- `capabilities` names the optional parts of that surface you implement. The
  writer lists every one this SDK provides. A hand-written file can list fewer.
- `freeTextUriArgs` names the `string` args of one tool that may fill a
  `{placeholder}`. `freeTextInUri` writes it. A tool that names none omits the
  key, and an omitted key opts nothing in.
- `confirm` says whether Ari asks the user before it runs that tool. It
  defaults to `false`, so a tool that wants no prompt omits the key.
- A key with its default value is left out, so the file stays short.

The file carries no package id. Ari takes it from your installed APK, so a
declared one would only be a second source of truth.

#### What a mistake costs you

Ari checks each tool on its own. One invalid tool is dropped and the rest of
the file still loads. Ari logs the reason for the drop, so read its logcat
output while you integrate.

A wrong top-level field costs more. A file with no `declarationVersion` fails
as a whole, and Ari reads no tool from it.

Your app sees no error either way. A dropped tool simply never reaches the LLM.
That is what the drift test protects you from.

### 3. Publish the service — `AndroidManifest.xml`

```xml
<service android:name=".AriToolService" android:exported="true"
         android:permission="com.ari_os.ari.permission.BIND_TOOL_PROVIDER">
    <intent-filter>
        <action android:name="com.ari_os.ari.action.TOOL_PROVIDER"/>
    </intent-filter>
</service>
```

The manifest holds no pointer to the declaration. The asset path is fixed, so
a typo cannot silently drop your provider.

The `BIND_TOOL_PROVIDER` permission is **declared by Ari**, not by your app —
do not add a `<permission>` element for it. Requiring it on your service is
what stops any app other than Ari from binding you.

The SDK enforces the same permission on every AIDL call, so a missing
`android:permission` attribute does not open your tools to other apps. It logs
an error at bind time instead. Keep the attribute anyway: it makes Android
reject the bind before your process even starts.

A provider whose tools are all deeplinks needs no service at all. It needs one
`<application>` meta-data flag instead, so Ari can find it:

```xml
<application ...>
    <meta-data android:name="com.ari_os.ari.tools" android:value="true"/>
</application>
```

The name is `AriToolsContract.META_DATA_TOOL_PROVIDER`. It reads the same as
Ari's tools authority and means something else, so copy it exactly.

Ari finds a provider two ways: the service `intent-filter` above, or this flag.
A provider that ships a service needs no flag. **Without a service and without
the flag your app is invisible to Ari.** It is never a candidate, so no tool
reaches the LLM and nothing is logged.

### 4. Write the handlers

A handler receives the arguments and returns the outcome Ari reports:

```kotlin
tool("set_circle_color", "Sets the colour of the circle shown in the app.") {
    enum("color", values = listOf("red", "green", "blue"), required = true)
    handle { args ->
        val color = args.string("color")
        applyColor(color)
        AriToolResult.ok { putString("color", color) }
    }
}
```

Inside `handle { }`, `this` is the `AriToolCall` that carries who called and
under which id:

- `callerPackage` is the app that bound you, read from the binder transaction.
  The permission is the gate, so you rarely need this. Check it when one tool
  needs a stronger rule than the rest. It is empty when the platform names no
  single package for the caller.
- `requestId` is the id Ari cancels the invocation by.

A handler runs on the main dispatcher, so touching UI state is safe — and
blocking there stalls your own main thread. Wrap slow work in
`withContext(Dispatchers.IO)`. The SDK does not expose coroutines on its
compile classpath, so declare `kotlinx-coroutines-core` in your own build to
use `withContext`.

Each invocation gets its own coroutine. Ari can therefore have two calls to the
same tool in flight, and they interleave at every suspension point. The SDK
does not queue them, so one slow tool never blocks another. Guard any state two
calls share.

Ari receives exactly one result for every call. The SDK reports one even when
your code throws, when Ari cancels the call, and when Android destroys the
service while the tool still runs.

Report a failure by returning an `AriToolResult.error(...)`, described in "The
error envelope". A thrown exception is only a fallback: the SDK must survive
one for IPC, so it logs the exception and returns the `app_error` code instead.

`tools()` runs on a binder thread once per call, and on the main thread when
Android creates your service. So read only state that is safe on both, and
hold the registry in a field when building it costs anything.

#### Reading the arguments

`ToolArgs` wraps the arguments as an `org.json.JSONObject`. Each declared type
has two accessors:

| Declared type | Returns a value or throws | Returns `null` when it cannot read |
|---|---|---|
| `string`, `enum` | `args.string("color")` | `args.stringOrNull("color")` |
| `int` | `args.int("count")` | `args.intOrNull("count")` |
| `number` | `args.number("ratio")` | `args.numberOrNull("ratio")` |
| `bool` | `args.bool("loud")` | `args.boolOrNull("loud")` |

`args.has("color")` reports whether an argument holds a value. A JSON `null`
counts as absent.

Prefer the `OrNull` form for an argument you declared as optional, so you can
say what a missing value means for that tool.

The strict form throws `AriToolArgumentException`, which the SDK reports as
`invalid_argument` with the name of the argument it could not read. A missing
or wrong-typed argument is the model's mistake, so Ari can ask it for better
arguments. The exception extends `IllegalArgumentException`, so a `catch` you
already have keeps working.

An accessor reads a value of another type when the meaning is unambiguous:
`"2"` reads as `2` for `int`, and `2` reads as `"2"` for `string`. It returns
`null` instead of guessing when the value would lose information: `1.5` is not
an `int`, `"yes"` is not a `bool`, and an object or an array is never text.

"Testing a handler" runs a whole tool call from a unit test. To test a helper
below the handler, build the arguments and the call directly:

```kotlin
val args = ToolArgs(JSONObject("""{"color":"red"}"""))
val call = AriToolCall("com.ari_os.ari", "req-1")
```

#### Result payloads

`AriToolResult.ok { }` builds the payload with one accessor per JSON type:

```kotlin
AriToolResult.ok {
    putString("color", "red")
    putInt("count", 3)
    putNumber("ratio", 1.5)
    putBool("enabled", true)
    putObject("size") { putInt("width", 10) }
    putList("items") {
        addString("a")
        addObject { putString("name", "row") }
    }
}
```

| Accessor | Emits |
|---|---|
| `putString`, `putInt`, `putNumber`, `putBool` | the matching JSON primitive, or JSON null for a null value |
| `putObject(name) { }` | a nested object, filled by the same accessors |
| `putList(name) { }` | a JSON array, filled by `addString`, `addInt`, `addNumber`, `addBool`, `addObject` and `addList` |

There is no accessor for any other type, so nothing reaches the payload as an
accidental `toString()`. A `LocalDate`, a `File` or an enum entry is a
**compile** error until you say how you want it written:

```kotlin
// Does not compile — no accessor takes a LocalDate.
AriToolResult.ok { putString("due", dueDate) }

// Right — you chose the format the LLM reads.
AriToolResult.ok { putString("due", dueDate.toString()) }
```

A `List` or a `Map` is a compile error for the same reason. Write it with
`putList`, which keeps it a JSON array instead of a string the LLM reads as
prose.

The payload is built on `org.json`, which ships in the framework, so no JSON
type from any library appears in your code.

`putNumber` and `addNumber` reject `NaN` and the infinities, because JSON has
no way to write them. Map such a value to text or to `null` yourself.

#### Opening a screen from a handler

A tool can open one of your own screens instead of returning data:

```kotlin
tool("open_work_order", "Opens the work order with this number.", presentsUi = true) {
    int("number", "The work order number, as printed on the job sheet.", required = true)
    handle { args ->
        AriToolResult.launch(
            PendingIntent.getActivity(
                this@AriToolService,
                0,
                workOrderIntent(args.int("number")),
                PendingIntent.FLAG_IMMUTABLE,
            ),
            spoken = "Opening work order ${args.int("number")}",
        )
    }
}
```

`presentsUi = true` tells Ari the tool opens a screen and returns no data.

Your service is a bound background process, so it cannot start an activity
itself. You hand Ari a `PendingIntent` and Ari sends it. The activity starts as
**your** app, with your permissions, so the target activity may be unexported.
It is the pattern a notification uses.

`FLAG_IMMUTABLE` is mandatory. It is what makes the intent Ari passes at send
time ignored. Without it Ari could fill in every extras key you left unset,
because `Intent.fillIn` treats each key as its own field. The SDK rejects a
mutable `PendingIntent`, and Ari rejects one whose creator is not your package
or whose target is not an activity.

`spoken` is what Ari says as the screen opens. Write it in the language your
user reads.

The `PendingIntent` crosses Binder next to the envelope, because JSON cannot
hold a `Parcelable`. The envelope itself carries no data:

```json
{"ok": true, "kind": "launch", "spoken": "Opening work order 42"}
```

**Android 11 cannot serve a launch result.** `PendingIntent.isImmutable()`
arrived in Android 12, so on Android 11 nothing can check the flag, and
`AriToolResult.launch(...)` returns an `unavailable` failure instead of a
launch the SDK cannot vouch for. Declare a `deeplink` tool to open a screen on
Android 11.

#### Testing a handler

`invokeToolInTest` runs one tool call from a unit test and returns the result
Ari reads:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class AriToolServiceTest {

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `set_circle_color returns the colour it set`() {
        val result = AriToolService().invokeToolInTest(
            "set_circle_color",
            """{"color":"red"}""",
        )

        assertEquals("red", (result as AriToolResult.Ok).payload.string("color"))
    }

    @Test
    fun `a colour the model left out is the model's mistake`() {
        val result = AriToolService().invokeToolInTest("set_circle_color", "{}")

        val failure = result as AriToolResult.Failure
        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
    }
}
```

The call enters your service where Ari enters it, so one test covers the
permission gate, the caller, the size caps, the argument parsing and the error
envelope. There is no second code path for a test to prove.

Only a test may call it. Shipped code calls it outside a binder transaction,
where `enforceCallingPermission` always throws.

`AriToolResult.Ok.payload` reads the payload by name and type, with the same
accessors as `ToolArgs`.

Your test module needs three things:

```kotlin
android {
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("org.json:json:<version>")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:<version>")
}
```

`Dispatchers.resetMain` is an experimental coroutines API, so the test class
needs the `@OptIn` the example shows.

Each missing piece fails in its own way. Without `Dispatchers.setMain` the call
throws and names the missing dispatcher. Without `org.json` the stubbed one
returns defaults, the call reports nothing at all, and `invokeToolInTest` throws
to say so.

To name the caller your handler reads, override `callingPackage()` in a test
subclass of your service:

```kotlin
class AriCallingToolService : AriToolService() {
    override fun callingPackage() = "com.ari_os.ari"
}
```

A JVM unit test cannot cover two things. The permission gate is a framework
call that the android unit-test jar turns into a no-op, so no test can show it
denying a caller. `AriToolResult.launch(...)` needs `PendingIntent.isImmutable`,
which the same jar cannot report, so a launch result reads as an `unavailable`
failure. Cover both on a device.

#### Size caps

A result crosses Binder as a JSON string and then enters the LLM prompt, so
both ends need a ceiling.

| Limit | Value | What breaks it |
|---|---|---|
| `AriToolsContract.MAX_RESULT_BYTES` | 64 KiB | the result reports `app_error` and never crosses Binder |
| `AriToolsContract.MAX_ARGS_BYTES` | 8 KiB | the call reports `invalid_argument` and no handler runs |

Both count **UTF-8 bytes of the encoded JSON**, not characters, because that is
what Binder limits. One euro sign is three bytes, so a payload can look small
in characters and still break the cap.

The numbers come from the Binder buffer. A process shares about 1 MB of
transaction buffer across every call in flight, in both directions, and a
String crosses Binder as UTF-16. So a 64 KiB ASCII result costs about 128 KB of
that buffer, and eight of them fit at once. Arguments at 8 KiB cost about 16 KB,
which is a 64th of the buffer.

They are also generous for the prompt. 64 KiB of JSON is roughly 16k to 22k
tokens, which is already more than any useful tool result, and 8 KiB of
arguments is far more than eight declared tools can need.

A result over the cap is your bug, not a transport problem. Send an id or a
short summary and let the user open your app for the rest.

#### The error envelope

`AriToolResult.error("...")` sends your text and nothing else:

```json
{"ok": false, "kind": "failure", "error": "color is required"}
```

Ari cannot translate free text, so it speaks your text as you wrote it. Write
it in the language your user reads.

A code travels further. Ari maps a code to its own translated string, and uses
it to decide whether another try can help:

```kotlin
AriToolResult.error(AriToolErrorCode.UNAVAILABLE)
AriToolResult.error(AriToolErrorCode.DENIED, "the user closed the dialog")
```

```json
{"ok": false, "kind": "failure", "code": "denied", "error": "the user closed the dialog"}
```

| `AriToolErrorCode` | Return it when | Ari may try again |
|---|---|---|
| `INVALID_ARGUMENT` | the arguments do not fit the tool | yes, with new arguments |
| `UNKNOWN_TOOL` | you declare no tool with that name | no |
| `UNAVAILABLE` | you cannot run the tool right now | yes, later |
| `DENIED` | the user or a policy refused | no |
| `APP_ERROR` | your app failed while it ran the tool | no |

`UNAVAILABLE` is the third of the three states in section 5. Return it when
the tool exists, Ari offered it, and it still cannot run.

The text next to a code is optional. Ari treats it as **data**, never as an
instruction, and prefers the translation of the code over it. Keep it short and
free of user data.

`UNKNOWN_TOOL` is one you rarely return yourself. The SDK looks every call up
in your registry and reports it for you.

The SDK sets a code for you when your own code cannot:

| Wire code | The SDK sets it when |
|---|---|
| `unknown_tool` | your registry declares no tool with the name Ari sent |
| `app_error` | a handler throws, your result does not encode, `tools()` throws, or Ari invokes a `deeplink` tool it should have opened itself |
| `invalid_argument` | the arguments do not parse, break the size cap, or a strict `ToolArgs` accessor cannot read one |
| `cancelled` | Ari cancels the invocation, or Android destroys the service |
| `unavailable` | `AriToolResult.launch(...)` runs on Android 11, where nothing can read the immutable flag |

`cancelled` is the one code you cannot set. Only the SDK knows that an
invocation stopped, so `AriToolErrorCode` has no entry for it.

#### The envelope kind

Every envelope carries a `kind`: `"ok"` for a result, `"failure"` for a
failure and `"launch"` for a screen to open. `ok` stays next to it, so a reader
that only looks at `ok` keeps working.

`kind` exists so a later SDK can add another kind. A reader on an older SDK
meets a `kind` it does not know and reports the `app_error` code, instead of
failing to parse the envelope. So a new kind costs no version bump on either
side. Read an envelope with `AriToolResult.fromJson(payload, launchIntent)`,
which applies that rule for you.

You cannot invent a code either. `AriToolResult.Failure` has an internal
constructor, so the two `error` factories are the only way to build a failure.

#### Cancellation

A tool that waits for a person can wait a long time. Ari cancels the
invocation when it gives up, and Android cancels every running invocation when
it destroys your service. Both cancel the coroutine that runs the handler, and
the SDK then reports the `cancelled` code.

Cancellation is cooperative, so your code has to let it through:

```kotlin
handle {
    val answer = withContext(Dispatchers.IO) { askTheUser() }   // cancels here
    AriToolResult.ok { putString("answer", answer) }
}
```

Never catch `CancellationException`, and never let a `catch (e: Exception)`
swallow it. A tool that swallows it keeps running after Ari stopped waiting,
and its result is thrown away. Release your own resources in a `finally`
block instead.

Code with no suspension point cannot be cancelled. It runs to the end and its
real result is reported, which is correct: a cancel is a request, not a kill.

### 5. Optional — say which tools are available right now

Some tools only make sense sometimes. A work-order tool needs a signed-in
user; a print tool needs a printer. Two channels carry that, and they run in
opposite directions:

| | Catalogue | Availability |
|---|---|---|
| Holds | every tool your app can ever expose | the subset that is live right now |
| Lives in | `assets/ari_tools.json`, written from your registry | Ari's own store, keyed by your package |
| Direction | Ari reads your APK | you push to Ari |
| Checked | at build time, by the drift test | against your catalogue, as Ari stores it |

At session connect Ari resolves the two locally. A tool reaches the LLM only
when your catalogue, your pushed set and the user's scope all hold it. Ari
binds nothing and starts no app of yours to work that out.

#### Three states, not two

This is the part worth reading twice.

| State | How you say it | What the model sees |
|---|---|---|
| The tool does not exist | omit it from the registry, and from the asset | nothing; Ari never knew of it |
| The tool exists, but not now | omit its name from `setAvailable` | nothing; Ari hides it for this session |
| The tool exists, was tried, and cannot run | return `AriToolErrorCode.UNAVAILABLE` | an `unavailable` failure it can report or retry |

Availability is visibility. The `unavailable` code is correctness.

A pushed set can be stale: your process may not have run since the state
changed, or the push may not have landed. A result code cannot be stale,
because the running tool produces it. So never rely on availability to stop a
tool running. Check again in the handler, and return
`AriToolErrorCode.UNAVAILABLE` when the answer changed.

#### Pushing the set

```kotlin
AriTools.setAvailable(
    context,
    setOf("open_work_order", "close_work_order"),
    registry = HpFieldTools.registry,
)
```

You send the **whole set** every time, never a delta. Two calls with the same
names have the same effect, and the order of the names does not matter. Send
an empty set to hide every tool.

`setAvailable` is a `suspend` function. It makes one IPC call, on
`Dispatchers.IO`, so calling it from the main thread is safe.

It tells you what happened, because a provider call returns a value:

| `AriAvailabilityResult` | Means | What to do |
|---|---|---|
| `Accepted` | Ari holds your set | nothing |
| `AriMissing` | this device runs no Ari | nothing, and do not retry |
| `Rejected(reason)` | Ari refused the set | fix it; the same set fails again |
| `Failed(reason)` | the set never reached Ari | try again later |

The `registry` argument is optional, and worth passing. With it the SDK
rejects a name your app does not declare, before any IPC. A typo then throws
where you wrote it, instead of quietly never matching. Without it the names go
to Ari, which validates them against your catalogue.

The set is a subset of your catalogue, so it holds at most
`AriToolsContract.MAX_TOOLS_PER_PROVIDER` names. Every name must match the
same `^[a-z][a-z0-9_]{0,31}$` your declarations do. Both rules are checked
before the call, and both throw `IllegalArgumentException`.

No permission is involved, and you pass no package name. Ari reads
`Binder.getCallingUid()` to see who called, so a package name you sent would
only be a claim it cannot trust.

#### Correcting a stale set at start-up

Ari keeps your set until you replace it, so the set can outlive the state that
produced it. Override one method and the SDK pushes your current answer
whenever your service is created:

```kotlin
class AriToolService : AriToolProviderService() {

    override fun tools() = HpFieldTools.registry

    override fun availableTools(): Set<String> =
        if (session.isSignedIn) setOf("open_work_order", "close_work_order") else emptySet()
}
```

The default returns `null`, which means "every declared tool is available". A
provider with no dynamic availability overrides nothing and pushes nothing.

That one push is not enough on its own. It runs when Android creates your
service, not when your state changes. Call `AriTools.setAvailable` yourself
the moment the answer changes: a sign-in, a sign-out, a printer that stops
answering.

An exception from `availableTools()`, or a name in it you never declared, is
logged as a warning. Neither ends your process.

## The two version numbers

The asset carries two numbers. They mean different things and Ari matches them
by different rules.

| Field | Covers | Ari's rule |
|---|---|---|
| `declarationVersion` | the declaration **format**: which fields and `type` values are legal in `ari_tools.json` | a **range**, `1..DECLARATION_VERSION`. Its own version or older. |
| `protocolVersion` | the **AIDL surface** you speak: the methods and their parameters | a **floor**, `>= MIN_SUPPORTED_PROTOCOL_VERSION`. No upper bound. |

The writer sets both. `declarationVersion` is `2` today and `protocolVersion`
is `1`.

During discovery Ari reads only the asset, so these two numbers and your
`capabilities` are all it knows about you. Your process is never started at
that point.

### Why the format is a range and the protocol is a floor

A newer *format* can be genuinely unreadable, so Ari refuses one it does not
know. A newer *surface* is always safe, because the surface only grows: Ari
calls what you declared and nothing else. An incompatible change to a method
means a second AIDL interface next to the first one, never a higher number. So
`protocolVersion` needs no ceiling.

The floor exists because you and Ari ship on your own schedule. `onResult`
gained its `PendingIntent` parameter, which changed that method's parcel
layout. Two sides built either side of such a change cannot read each other's
parcels, so Ari needs to refuse the older surface before it calls anything.
The floor is where it does that.

### What your capabilities say

`capabilities` names the optional parts of the surface you implement:

| Capability | You implement |
|---|---|
| `cancel` | `IAriToolProvider.cancel`, so Ari can stop an invocation |
| `launch_result` | a `launch` result carrying a `PendingIntent` |

`invoke` is not a capability. A provider Ari cannot invoke is not a provider,
so `protocolVersion` covers that method instead.

Ari routes only what you declared. Without `cancel` it stops waiting on its own
side rather than calling you. Without `launch_result` it drops every tool that
sets `presentsUi` without a `uri`, because you could not serve one.

Three states, again:

| In the asset | Ari reads it as |
|---|---|
| no `capabilities` key | every capability that existed at your `protocolVersion` |
| `"capabilities": []` | you implement nothing optional |
| `"capabilities": ["cancel"]` | that list, minus anything Ari does not know |

An absent key and an empty list are **not** the same. An absent key means the
file was written before the key existed, so Ari falls back to what your
declared `protocolVersion` guarantees. An empty list is you saying you have
none. A file this SDK writes always names the key, so the fallback only ever
applies to an older file.

### When each number changes

Bump `DECLARATION_VERSION` only when a change makes an older Ari **misread** a
declaration. That means a new `type` value, a changed field meaning, or a new
required field. A new optional field that an older Ari ignores is not a bump,
unless ignoring it changes how Ari dispatches the tool. Two worked cases:

- An older Ari that cannot see `uri` would bind the provider instead of opening
  the link. That is a bump.
- An older Ari ignores an unknown key rather than failing the file. So it would
  read a file that says `"capabilities": []` as a version-1 file with no
  capability data at all, then call `cancel` on a provider that has none and
  offer launch tools it cannot serve. That is why `capabilities` and
  `protocolVersion` came with a bump to `2`.

Bump `PROTOCOL_VERSION` when the AIDL surface gains something optional, and
give the new capability an entry in
`AriToolsContract.CAPABILITY_SINCE_PROTOCOL_VERSION`. Raise
`MIN_SUPPORTED_PROTOCOL_VERSION` only to drop an older surface on purpose.

Every value in `AriToolsContract` is public API. Changing one is a breaking
change, so bump a version constant instead of redefining an existing value.
