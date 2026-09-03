# Ari Tool Sample — numbered circles

A minimal Android app that exposes four capabilities to Ari. Install it, then say:

> "Hey Ari, add a blue circle"

**The app does not need to be open** — Ari reads its capabilities without launching it.

## The tools

| Tool | Args | Notes |
|---|---|---|
| `add_circle` | `color?` | Appends a circle, returns its number. Defaults red. |
| `remove_circle` | `number` | `"confirm": true` — Ari asks first. Other circles keep their numbers. |
| `set_circle_color` | `color`, `number?` | Omit the number to recolour every circle. |
| `list_circles` | none | How Ari answers "what's on screen?" — it can't see the display. |

Circle numbers are **permanent**, so they can have gaps. Remove circle 2 of 4
and the rest stay 1, 3 and 4. That is what makes "remove the purple ones" safe:
Ari issues one removal per match from a single `list_circles`, often before the
first result comes back, and no number has shifted under it.

Every description in the declaration says the numbers are permanent and can
have gaps. That is deliberate — without it the model assumes numbers run 1..N
and guesses wrong after a removal.

## Two lessons worth more than the code

**Prefer idempotent tools.** `set_circle_color` is idempotent — setting blue twice
looks identical to once. `add_circle` is not, so when the language model
occasionally regenerates a turn and repeats its tool call, you get a circle you
never asked for. Same model flakiness, wildly different consequence. Where a tool
must accumulate or destroy, say so in the description (see `add_circle`) and
consider `"confirm": true`.

**Ari narrates before it knows.** The model often says "adding it" before reading
the tool result, so a failure can be spoken as a success. Return precise error
text — Ari reads it out — and don't rely on the user hearing the difference.

## How it works

Three files, and you write all three:

1. **`app/src/main/assets/ari_tools.json`** declares the tools: each one's name,
   a description the language model reads, and its typed arguments. The path is
   **fixed** (`AriToolsContract.DECLARATION_ASSET`), so there is nothing to
   point at and nothing to misspell.
2. **`app/src/main/AndroidManifest.xml`** publishes a service with the
   `com.ari_os.ari.action.TOOL_PROVIDER` action, protected by
   `com.ari_os.ari.permission.BIND_TOOL_PROVIDER` so only Ari can bind it. It
   holds **no pointer to the declaration** — a manifest-supplied path would
   still compile with a typo and then drop the provider at runtime, silently.
3. **`AriToolService.kt`** extends `AriToolProviderService` and implements
   `onInvoke`. That is the entire integration.

At session start Ari opens the asset straight out of this app's installed APK —
`getResourcesForApplication(...).assets.open("ari_tools.json")`, so no IPC and
no app launch — and tells the cloud the tools exist. When you ask for one, the
call arrives over AIDL at `onInvoke`.

## Declaring tools

This app's own declaration, cut down to one tool and a shorter colour list —
see `app/src/main/assets/ari_tools.json` for all four:

```json
{
  "package": "com.example.aridemo",
  "declarationVersion": 1,
  "tools": [
    {
      "name": "set_circle_color",
      "description": "Changes the colour of one circle by its number, or of every circle if no number is given.",
      "confirm": false,
      "args": [
        {
          "name": "color",
          "type": "enum",
          "values": ["red", "green", "blue"],
          "required": true,
          "description": "The colour to change to."
        },
        {
          "name": "number",
          "type": "int",
          "required": false,
          "description": "The circle's permanent number. Leave out to recolour all of them."
        }
      ]
    }
  ]
}
```

- `package` — your application id. Ari trusts the package the declaration
  actually shipped in, so a mismatch here is logged and ignored rather than
  fatal.
- `declarationVersion` — **required**. It is the declaration *format* version
  (`AriToolsContract.DECLARATION_VERSION`, currently `1`), not the AIDL
  version. Leave it out and the **whole file** is rejected; it is not read as
  version `1`. Ari accepts its own version or older and rejects a newer one.
- `label` — optional display name for your app. Omitted here, so Ari falls back
  to the manifest label.
- `name` — tool and arg names must match `^[a-z][a-z0-9_]{0,31}$`.
- `description` — required on a tool, max 300 chars. It is spent on every LLM
  turn, so keep it short and specific.
- `type` is one of `string`, `int`, `number`, `bool`, `enum`.
- `values` is a **JSON array of strings**, required for `"type": "enum"` and
  rejected on any other type.
- `"confirm": true` makes Ari ask the user before the tool runs. Set it on
  anything destructive.
- At most 8 tools per provider.

Prefer `enum` where the value space is closed. It constrains the model to real
values instead of letting it invent `"cerulean"`.

Anything invalid is dropped silently: the tool simply never reaches the model,
with no error in your app. Check Ari's logcat while integrating.

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
| `Skipping <pkg>: no ari_tools.json asset` | The declaration is not at `app/src/main/assets/ari_tools.json`, or the filename is misspelt. |
| `Skipping <pkg>: no declaration version` | `declarationVersion` is missing from the top-level object. The whole file is rejected. |
| `Skipping <pkg>: failed to read or decode ari_tools.json` | Malformed JSON, or a field of the wrong type. |
| `tool '<name>' dropped: ...` | That one tool failed validation — bad name, missing description, `values` on a non-enum. The other tools still load. |

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

**The copy currently predates the switch to `assets/ari_tools.json`.** It still
carries `AriToolsContract.META_DATA_TOOLS` and an XML parser, both of which are
gone upstream. The app compiles against it only for `AriToolProviderService`
and `AriToolResult`, and never reads its own declaration, so the staleness is
harmless here — but read this README, not that copy, for the declaration
contract. The re-sync lands once leviathan #719 merges.
