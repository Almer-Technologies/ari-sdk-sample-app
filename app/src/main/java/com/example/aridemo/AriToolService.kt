package com.example.aridemo

import com.ari_os.ari.sdk.AriToolErrorCode
import com.ari_os.ari.sdk.AriToolProviderService
import com.ari_os.ari.sdk.AriToolRegistry
import com.ari_os.ari.sdk.AriToolResult
import com.ari_os.ari.sdk.ariTools

/**
 * Exposes this app's capabilities to Ari.
 *
 * Every tool is declared here, in code, next to the function that runs it. One
 * registry answers both questions Ari asks: `tools()` is what Ari reads to
 * learn the tools exist, and the same registry is what the SDK dispatches an
 * invocation through. So a tool and its handler cannot drift apart, and the
 * `color` argument's allowed values come straight from [CircleState]'s palette
 * instead of a copy kept in step by hand.
 *
 * Five of the six tools work that way. `show_circle` is the other kind: it is
 * declared with a `uri` and no handler, so Ari opens the link itself and never
 * binds this service. It is here only because a tool is declared in one place
 * whatever runs it — its code is [MainActivity] and the manifest's intent
 * filter, not this class.
 *
 * `assets/ari_tools.json` is written from this registry by
 * `AriToolsAsset.writeTo` and committed — see `AriToolsAssetTest`, which fails
 * the build when the committed file stops matching this code. Nothing in that
 * file is written by hand. Ari reads it straight out of the installed APK at
 * session connect — no IPC, and this app is never launched for it — so the
 * tools work even if it has never been opened.
 *
 * Each `description` is read by the language model, so it is written for a
 * reader who cannot see the screen: it says what the tool does and what the
 * argument means, because the model picks the tool from that text alone.
 *
 * This class is the entire integration surface — everything else is ordinary
 * app code.
 */
class AriToolService : AriToolProviderService() {

    // A field, not a fresh registry per call: the SDK calls tools() once per
    // invocation, and building one re-validates every name and description.
    private val registry = ariTools {

        /**
         * The description tells the model to "call this once per circle ... do
         * not repeat the call after it succeeds". That wording is deliberate.
         * This tool is not idempotent, so a repeated call adds a second circle
         * the user never asked for — and small models do sometimes regenerate a
         * turn and re-issue their tool call. The description is the cheapest
         * lever on that.
         */
        tool(
            name = "add_circle",
            description = "Adds exactly one circle and returns the permanent number assigned " +
                "to it. Call this once per circle the user asked for; do not repeat the call " +
                "after it succeeds. Fails if ${CircleState.MAX_CIRCLES} circles already exist.",
        ) {
            enum(
                "color",
                values = CircleState.supportedNames(),
                description = "Colour for the new circle. Defaults to " +
                    "${CircleState.DEFAULT_COLOR} if not given.",
            )
            handle { args -> addCircle(args.stringOrNull("color")) }
        }

        /**
         * One of the two tools declared with `confirm = true`. The flag is not
         * read today — Ari confirms every app tool and shows the argument
         * values, and a provider cannot opt out of that. It is set here because
         * it records which tools are the destructive ones, and a later SDK
         * version may honour it.
         *
         * The description used to end "it is safe to remove several circles in
         * one go using the numbers from a single list_circles call", which is
         * true and was the wrong thing to say: the model took it as licence to
         * answer "remove all the green circles" with one call per match, and
         * because Ari confirms every call, the user was asked twice. That is
         * what `remove_circles_by_color` exists for, so this description now
         * points at it and claims only the single-circle case.
         */
        tool(
            name = "remove_circle",
            description = "Removes exactly one circle, the one with this number. For every " +
                "circle of a colour, call remove_circles_by_color once instead of calling this " +
                "once per match. Other circles keep their own numbers, so a number from an " +
                "earlier list_circles is still correct.",
            confirm = true,
        ) {
            int(
                "number",
                description = "The circle's permanent number, as shown on it and reported by " +
                    "list_circles. Not a position, so do not assume the circles are numbered " +
                    "1 upwards.",
                required = true,
            )
            // Required, so the strict accessor is right: a missing or non-numeric
            // value is the model's mistake, and the SDK reports it as
            // invalid_argument so Ari can ask for better arguments.
            handle { args -> removeCircle(args.int("number")) }
        }

        /**
         * The plural sibling of `remove_circle`. It exists to cut a count of
         * confirmations, not to save a round trip.
         *
         * Ari asks the user before every app tool call and a provider cannot
         * opt out, so N calls means N prompts. Observed on a headset: "remove
         * all the green circles" produced two parallel `remove_circle` calls
         * and the user had to say yes twice. Modelling the plural intent as one
         * tool makes it one call and therefore one prompt. `confirm = true`
         * does not achieve that and is not what fixed it — it is set because
         * this tool is destructive, the same reason `remove_circle` sets it.
         *
         * Both descriptions carry the boundary, because the descriptions are
         * the only thing the model has to choose between them: this one says
         * every circle of a colour, `remove_circle` says exactly one by number.
         * `add_circle` already steers decomposition the same way.
         *
         * Removing nothing is a success, not an error. There is no failure to
         * report — the screen already holds no circle of that colour — so the
         * result carries `removed` 0 and the description tells the model what
         * to say about it, since the risk is Ari narrating a removal that never
         * happened.
         */
        tool(
            name = "remove_circles_by_color",
            description = "Removes every circle of one colour in a single call. Use it " +
                "whenever the user asks for all the circles of a colour, instead of calling " +
                "remove_circle once per match. Reports how many it removed; 0 means there were " +
                "none of that colour, so say nothing was removed.",
            confirm = true,
        ) {
            enum(
                "color",
                values = CircleState.supportedNames(),
                description = "Every circle of this colour is removed. The circles of other " +
                    "colours stay.",
                required = true,
            )
            handle { args -> removeCirclesByColor(args.string("color")) }
        }

        /**
         * `number` is declared optional on purpose: omitted means every circle.
         * That keeps the simple phrasing ("change the colour to blue") working
         * when there is only one.
         */
        tool(
            name = "set_circle_color",
            description = "Changes the colour of one circle by its number, or of every circle " +
                "if no number is given.",
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

        /**
         * Declared with no arguments. Ari cannot see the screen, so this is how
         * it answers questions about what is displayed, and how it maps a
         * colour back to a number before removing or recolouring.
         */
        tool(
            name = "list_circles",
            description = "Reports every circle on screen with its permanent number and " +
                "colour. Numbers can have gaps. Call this first when the user names a circle " +
                "by colour or position rather than by number. Removing every circle of a " +
                "colour needs no numbers: remove_circles_by_color does it in one call.",
        ) {
            handle { listCircles() }
        }

        /**
         * The only tool with no handler, and the only one this service never
         * runs. Ari fills `{number}` from the `number` argument and opens
         * `aridemo://circle/3` as `ACTION_VIEW` on this package; the manifest's
         * intent filter routes it to [MainActivity], which highlights that
         * circle. Nothing binds this service, and the SDK reports `app_error`
         * if Ari ever invokes it instead of opening the link.
         *
         * `{number}` is filled by an `int`, which is what the SDK allows: only a
         * type whose values the declaration constrains — `int`, `number`, `bool`
         * or `enum` — may fill a placeholder, because the value goes into a uri
         * another component then handles. A free-text `string` is rejected here,
         * as you build the registry. That costs nothing in this app, since a
         * circle's identity really is a whole number; a tool that has to take a
         * phrase belongs in a `tool { }` with a `handle { }` block that
         * validates the text and returns `AriToolResult.launch(...)`.
         *
         * The template is [CircleDeeplink.TEMPLATE], so what Ari is told to open
         * and what the app parses are one constant rather than two strings that
         * agree today.
         */
        deeplink(
            name = "show_circle",
            description = "Opens this app with the circle of this number highlighted. Use it " +
                "when the user asks to see or point out a circle rather than to change one.",
            uri = CircleDeeplink.TEMPLATE,
        ) {
            int(
                "number",
                description = "The circle's permanent number, as shown on it and reported by " +
                    "list_circles. Not a position.",
                required = true,
            )
        }
    }

    override fun tools(): AriToolRegistry = registry

    private fun addCircle(requested: String?): AriToolResult {
        val color = requested ?: CircleState.DEFAULT_COLOR
        if (CircleState.colorOf(color) == null) return unknownColor(color)

        val number = CircleState.add(color)
            ?: return AriToolResult.error(
                AriToolErrorCode.UNAVAILABLE,
                "There are already ${CircleState.MAX_CIRCLES} circles, " +
                    "which is the maximum. Remove one first.",
            )
        return AriToolResult.ok {
            putInt("number", number)
            putString("color", color)
            putInt("total", CircleState.count)
        }
    }

    private fun removeCircle(number: Int): AriToolResult {
        if (!CircleState.remove(number)) return noSuchCircle(number)
        return AriToolResult.ok {
            putInt("removed", number)
            putInt("total", CircleState.count)
        }
    }

    /**
     * `removed` is a count here, and the circle's number in [removeCircle]'s
     * result. That reads as a collision and is the established shape:
     * `set_circle_color` already answers with `changed`, a count of the circles
     * one call touched. The tool name travels with the result, so the model
     * reads this one as the plural tool's answer.
     */
    private fun removeCirclesByColor(color: String): AriToolResult {
        val removed = CircleState.removeByColor(color) ?: return unknownColor(color)
        return AriToolResult.ok {
            putString("color", color)
            putInt("removed", removed)
            putInt("total", CircleState.count)
        }
    }

    private fun setCircleColor(color: String, number: Int?): AriToolResult {
        if (CircleState.colorOf(color) == null) return unknownColor(color)

        // Absent `number` means every circle — see the tool's declaration.
        val changed = CircleState.setColor(color, number) ?: return noSuchCircle(number ?: 0)
        return AriToolResult.ok {
            putString("color", color)
            putInt("changed", changed)
        }
    }

    private fun listCircles(): AriToolResult {
        val circles = CircleState.circles.value
        return AriToolResult.ok {
            putInt("total", circles.size)
            // Keyed by the circle's permanent number: the model reads this back
            // to the user, and uses it to map a colour onto a number before
            // acting. putObject keeps it a JSON object, so nothing arrives as
            // prose the model has to re-parse.
            putObject("circles") {
                circles.forEach { circle -> putString("${circle.number}", circle.colorName) }
            }
        }
    }

    /**
     * Unreachable while the declaration holds: the `color` enum's values and
     * this resolver are now the same list. Kept as the backstop for a value Ari
     * sends off-list.
     */
    private fun unknownColor(color: String) = AriToolResult.error(
        AriToolErrorCode.INVALID_ARGUMENT,
        "I don't know the colour '$color'. Try one of: " +
            CircleState.supportedNames().joinToString(", "),
    )

    /**
     * Numbers are permanent and can have gaps, so a plain "no such circle" is
     * not enough — naming the numbers that do exist is what lets Ari (and the
     * user) recover without another round trip.
     */
    private fun noSuchCircle(number: Int): AriToolResult {
        val active = CircleState.activeNumbers()
        return AriToolResult.error(
            AriToolErrorCode.INVALID_ARGUMENT,
            when {
                active.isEmpty() -> "There are no circles on screen."
                else -> "There's no circle $number. The circles are " +
                    "${active.joinToString(", ")}."
            },
        )
    }
}
