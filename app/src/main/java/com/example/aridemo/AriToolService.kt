package com.example.aridemo

import com.ari_os.ari.sdk.AriToolProviderService
import com.ari_os.ari.sdk.AriToolResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Exposes this app's capabilities to Ari.
 *
 * Declared in `assets/ari_tools.json` and published via the manifest service
 * entry. Ari reads that asset straight out of the installed APK at session
 * connect — no IPC, and this app is never launched for it — so the tools work
 * even if it has never been opened.
 *
 * Each tool's `description` in that file is read by the language model, so it
 * is written for a reader who cannot see the screen: it says what the tool
 * does and what the argument means, because the model picks the tool from that
 * text alone.
 *
 * This class is the entire integration surface — everything else is ordinary
 * app code.
 */
class AriToolService : AriToolProviderService() {

    override suspend fun onInvoke(tool: String, args: JsonObject): AriToolResult =
        when (tool) {
            "add_circle" -> addCircle(args)
            "remove_circle" -> removeCircle(args)
            "set_circle_color" -> setCircleColor(args)
            "list_circles" -> listCircles()
            else -> AriToolResult.error("This app has no tool called '$tool'.")
        }

    /**
     * The declared description tells the model to "call this once per circle
     * ... do not repeat the call after it succeeds". That wording is
     * deliberate. This tool is not idempotent, so a repeated call adds a
     * second circle the user never asked for — and small models do sometimes
     * regenerate a turn and re-issue their tool call. The description is the
     * cheapest lever on that.
     */
    private fun addCircle(args: JsonObject): AriToolResult {
        val color = args.optionalString("color") ?: "red"
        if (CircleState.colorOf(color) == null) return unknownColor(color)

        val number = CircleState.add(color)
            ?: return AriToolResult.error(
                "There are already ${CircleState.MAX_CIRCLES} circles, " +
                    "which is the maximum. Remove one first."
            )
        return AriToolResult.ok("number" to number, "color" to color, "total" to CircleState.count)
    }

    /**
     * The only tool declared with `"confirm": true`, which makes Ari ask the
     * user before running it. Use that for anything destructive — removal
     * cannot be undone here.
     */
    private fun removeCircle(args: JsonObject): AriToolResult {
        val number = args.optionalInt("number")
            ?: return AriToolResult.error("Tell me which circle number to remove.")

        if (!CircleState.remove(number)) return noSuchCircle(number)
        return AriToolResult.ok("removed" to number, "total" to CircleState.count)
    }

    /**
     * `number` is declared `"required": false` on purpose: omitted means every
     * circle. That keeps the simple phrasing ("change the colour to blue")
     * working when there is only one.
     */
    private fun setCircleColor(args: JsonObject): AriToolResult {
        val color = args.optionalString("color")
            ?: return AriToolResult.error("Tell me which colour to use.")
        if (CircleState.colorOf(color) == null) return unknownColor(color)

        // Absent `number` means every circle — see the tool's declaration.
        val number = args.optionalInt("number")
        val changed = CircleState.setColor(color, number)
            ?: return noSuchCircle(number ?: 0)

        return AriToolResult.ok("color" to color, "changed" to changed)
    }

    /**
     * Declared with an empty `args` array. Ari cannot see the screen, so this
     * is how it answers questions about what is displayed, and how it maps a
     * colour back to a number before removing or recolouring.
     */
    private fun listCircles(): AriToolResult {
        val circles = CircleState.circles.value
        // Keyed by the circle's permanent number: the model reads this back to
        // the user, and uses it to map a colour onto a number before acting.
        val byNumber = buildJsonObject {
            circles.forEach { put("${it.number}", JsonPrimitive(it.colorName)) }
        }
        return AriToolResult.ok(
            buildJsonObject {
                put("total", JsonPrimitive(circles.size))
                put("circles", byNumber)
            }
        )
    }

    private fun unknownColor(color: String) = AriToolResult.error(
        "I don't know the colour '$color'. Try one of: " +
            CircleState.supportedNames().joinToString(", ")
    )

    /**
     * Numbers are permanent and can have gaps, so a plain "no such circle" is
     * not enough — naming the numbers that do exist is what lets Ari (and the
     * user) recover without another round trip.
     */
    private fun noSuchCircle(number: Int): AriToolResult {
        val active = CircleState.activeNumbers()
        return AriToolResult.error(
            when {
                active.isEmpty() -> "There are no circles on screen."
                else -> "There's no circle $number. The circles are " +
                    "${active.joinToString(", ")}."
            }
        )
    }
}

/**
 * Read an optional string argument.
 *
 * Ari strips arguments the user did not supply, so an absent key is the normal
 * case. `JsonNull` is checked explicitly because kotlinx reports its `content`
 * as the string `"null"`, which would otherwise sail through as a value.
 */
private fun JsonObject.optionalString(key: String): String? {
    val raw = this[key] ?: return null
    if (raw is JsonNull) return null
    return raw.jsonPrimitive.content.takeIf { it.isNotBlank() }
}

/** Read an optional int argument, tolerating a numeric string. */
private fun JsonObject.optionalInt(key: String): Int? {
    val raw = this[key] ?: return null
    if (raw is JsonNull) return null
    val primitive = raw.jsonPrimitive
    return runCatching { primitive.int }.getOrElse { primitive.content.toIntOrNull() }
}
