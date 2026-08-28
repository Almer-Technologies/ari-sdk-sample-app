# Ari Tool Sample — numbered circles

A minimal Android app that exposes four capabilities to Ari. Install it, then say:

> "Hey Ari, add a blue circle"

**The app does not need to be open** — Ari reads its capabilities without launching it.

## The tools

| Tool | Args | Notes |
|---|---|---|
| `add_circle` | `color?` | Appends a circle, returns its number. Defaults red. |
| `remove_circle` | `number` | `confirm="true"` — Ari asks first. Remaining circles renumber. |
| `set_circle_color` | `color`, `number?` | Omit the number to recolour every circle. |
| `list_circles` | none | How Ari answers "what's on screen?" — it can't see the display. |

Circle numbers are **positions**, so they always run 1..N with no gaps. Remove
circle 2 of 4 and the old 3 and 4 become 2 and 3. What you see is what you say.

## Two lessons worth more than the code

**Prefer idempotent tools.** `set_circle_color` is idempotent — setting blue twice
looks identical to once. `add_circle` is not, so when the language model
occasionally regenerates a turn and repeats its tool call, you get a circle you
never asked for. Same model flakiness, wildly different consequence. Where a tool
must accumulate or destroy, say so in the description (see `add_circle`) and
consider `confirm="true"`.

**Ari narrates before it knows.** The model often says "adding it" before reading
the tool result, so a failure can be spoken as a success. Return precise error
text — Ari reads it out — and don't rely on the user hearing the difference.

## How it works

Three pieces:

1. **`app/src/main/res/xml/ari_tools.xml`** declares the tool: its name, a
   description the language model reads, and its typed arguments.
2. **`app/src/main/AndroidManifest.xml`** publishes a service with the
   `com.ari_os.ari.action.TOOL_PROVIDER` action, protected by
   `com.ari_os.ari.permission.BIND_TOOL_PROVIDER` so only Ari can bind it, with
   a `<meta-data>` entry pointing at the declaration.
3. **`AriToolService.kt`** extends `AriToolProviderService` and implements
   `onInvoke`. That is the entire integration.

At session start Ari reads the declaration through `PackageManager` — no IPC, no
app launch — and tells the cloud the tool exists. When you ask for it, the call
arrives over AIDL at `onInvoke`.

## Declaring arguments

`type` is one of `string`, `int`, `number`, `bool`, `enum`. `values` is required
for `enum` and not allowed otherwise.

Prefer `enum` where the value space is closed. It constrains the model to real
values instead of letting it invent `"cerulean"`.

Set `confirm="true"` on anything destructive and Ari will ask the user before
running it.

### Optional arguments — read this before adding one

An optional argument the user didn't mention is **absent** from `args`, never
present-as-null. So the obvious check works:

```kotlin
val note = args["note"]?.jsonPrimitive?.content   // null when not supplied
```

That is only safe because Ari strips unsupplied optionals before dispatch. Do
**not** rely on `?.jsonPrimitive?.content` to detect a JSON null yourself — for
kotlinx's `JsonNull`, `content` is the **string `"null"`**, not Kotlin `null`, so
a null that did arrive would sail through the `?.` and hand you `"null"` as a
value. If you want to be defensive, test the type:

```kotlin
val raw = args["note"]
val note = if (raw is JsonNull) null else raw?.jsonPrimitive?.content
```

Falsy-but-meaningful values (`0`, `false`, `""`) are preserved and reach you
normally — only unsupplied optionals are dropped.

## Returning results

```kotlin
AriToolResult.ok("color" to color)          // success, with a payload
AriToolResult.ok()                          // success, nothing to report
AriToolResult.error("I don't know that.")   // failure; Ari says this to the user
```

Error text is spoken to the user. Write a short explanation, not a stack trace.

## Build and install

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements on the device:

- The **Ari app must be installed** — it defines the permission this app's
  service requires. Without it the service is unreachable.
- This app's package must be on Ari's provider allowlist
  (`AppToolTrust.PACKAGES` in the Ari app, and `TRUSTED_APP_TOOL_PACKAGES` in
  the Ari cloud). In the proof-of-concept both are compiled in; production ties
  provider eligibility to the app marketplace.

Tools are discovered when a session **starts**. If you install this app while
Ari is already in a conversation, end and restart the conversation.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Ari says it can't change colours | Package not on the allowlist, or discovery ran before install. Restart the session. |
| `SecurityException` on bind in logcat | The Ari app is missing `<uses-permission>` for its own `BIND_TOOL_PROVIDER`. Defining a permission does not grant it. |
| `no com.ari_os.ari.tools resource` | Manifest `<meta-data>` missing or misnamed. |
| `declaration version 0` | `apiVersion` missing from the `<ari-tools>` root element. |

Check discovery with:

```bash
adb logcat -d | grep -iE "AppToolRegistry|tool provider|appTools"
```

A healthy session logs `Discovered 1 app tool provider(s)`.

## Known limitation of this sample

`ari-tool-sdk/` is a **copy** of the real SDK module, because there is no
published artifact yet — leviathan has no Maven publishing, and `publish.sh`
ships APKs rather than AARs. `ari-tool-sdk/VENDORED_FROM.txt` records the commit
it was copied from.

Do not edit the copy. Change the real module in leviathan and re-copy.
