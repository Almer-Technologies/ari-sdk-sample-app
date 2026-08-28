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
 * Declared in `res/xml/ari_tools` and published via the manifest service entry.
 * Ari discovers it at session connect without launching the app.
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

    private fun removeCircle(args: JsonObject): AriToolResult {
        val number = args.optionalInt("number")
            ?: return AriToolResult.error("Tell me which circle number to remove.")

        if (!CircleState.remove(number)) return noSuchCircle(number)
        return AriToolResult.ok("removed" to number, "total" to CircleState.count)
    }

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
