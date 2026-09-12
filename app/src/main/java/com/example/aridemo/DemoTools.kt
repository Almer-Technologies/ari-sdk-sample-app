package com.example.aridemo

import com.ari_os.ari.sdk.AriToolErrorCode
import com.ari_os.ari.sdk.AriToolResult
import com.ari_os.ari.sdk.declaration.AriToolDeclarations
import com.ari_os.ari.sdk.declaration.ariTools

/**
 * Every tool this app exposes to Ari, and the code that runs each one.
 *
 * An `object` rather than a field on [AriToolService], because two readers need
 * it and only one of them is a device. The Ari Gradle plugin builds this object
 * in the build JVM to write `assets/ari_tools.json`, and [AriToolService]
 * returns the same [registry] from `tools()` so an invocation dispatches through
 * what was declared. One registry, so a tool and its handler cannot drift apart,
 * and the `color` argument's allowed values come straight from [CircleState]'s
 * palette instead of a copy kept in step by hand. `show_circle` is the exception
 * — a `uri` and no handler, so Ari opens the link itself and never binds the
 * service; its code is [MainActivity] and the manifest's intent filter.
 *
 * **Declaring a tool must call no Android framework method.** The build runs
 * this object against the plain `android.jar`, whose every method throws
 * `Stub!`, so a framework call here fails the build with that named as the
 * cause. Naming an Android type is fine — [CircleState.supportedNames] reads a
 * map of `androidx.compose.ui.graphics.Color`, which is a value class over a
 * `ULong` and so builds with no framework behind it. Keep framework calls in the
 * handlers, which only ever run on the device.
 *
 * Each `description` is written for a reader who cannot see the screen: the
 * model picks the tool from that text alone.
 *
 * `confirm` is read by the cloud and defaults to `false`, so every tool below
 * says why it does or does not prompt — a flag left off looks the same whether
 * it was decided or overlooked. Nothing verifies who declared it.
 *
 * `fixWith` goes the other way: exactly one failure here names one, and the rest
 * name nothing on purpose. Ari puts the named tool in front of the user, so the
 * field is only worth setting where that tool really does repair the call. See
 * [addCircle] for the one that does, and [unknownColor] and [noSuchCircle] for
 * the two that have nothing honest to offer.
 */
object DemoTools : AriToolDeclarations {

    /**
     * Read twice — declared below, and named as the fix when the screen is full.
     * The other five names appear once each, so they stay literals; this one
     * would otherwise be a copy kept in step by hand, which the SDK cannot catch
     * for us. An undeclared `fixWith` is dropped at runtime with only a log line.
     */
    private const val REMOVE_CIRCLE = "remove_circle"

    override val registry = ariTools {

        /**
         * Not idempotent, so a repeated call adds a circle nobody asked for, and
         * small models do regenerate a turn and re-issue their tool call. Hence
         * the "do not repeat the call" wording; a prompt would not help, since a
         * regenerated turn asks again and the user says yes again.
         *
         * No `confirm`: adding a circle is harmless and `remove_circle` undoes it.
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
         * `confirm = true`: the cloud asks the user before running this tool and
         * shows the argument values the call will send. This one is destructive.
         *
         * The description claims only the single-circle case and points at
         * `remove_circles_by_color`. It used to invite several removals from one
         * `list_circles`, which the model took as licence to answer "remove all
         * the green circles" with one prompting call per match.
         */
        tool(
            name = REMOVE_CIRCLE,
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
            // Required, so the strict accessor: the SDK turns a missing or
            // non-numeric value into invalid_argument for Ari to retry against.
            handle { args -> removeCircle(args.int("number")) }
        }

        /**
         * Exists to cut a count of confirmations, not to save a round trip: both
         * removal tools prompt, so N calls means N prompts, and on a headset
         * "remove all the green circles" produced two of each. Clearing `confirm`
         * would have silenced the second prompt too, and been the wrong fix — how
         * often the user is asked is a property of the tool surface.
         *
         * `confirm = true` for the same reason as `remove_circle`. Both
         * descriptions carry the boundary between the two, because that text is
         * all the model has to choose with.
         *
         * Removing nothing is a success: `removed` 0, and the description tells
         * the model to say so, since the risk is Ari narrating a removal that
         * never happened.
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
         * `number` is optional on purpose: omitted means every circle, which keeps
         * "change the colour to blue" working when there is only one.
         *
         * No `confirm`, and this is the closest call in the registry — omitting
         * `number` recolours everything and no previous colour is recorded, so
         * that call cannot be undone. It stays `false` because what is lost is a
         * circle's colour in a toy sample, and this is the README's example of an
         * idempotent tool. Copy the question, not the answer: an optional argument
         * whose absence widens what one call reaches is the shape to set
         * `confirm = true` on once what it overwrites is real.
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
         * No arguments. Ari cannot see the screen, so this is how it answers
         * questions about what is displayed, and how it maps a colour back to a
         * number before removing or recolouring.
         *
         * No `confirm`: it changes nothing, and it is the tool the model calls
         * first, so a prompt would land before the request was understood.
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
         * The only tool with no handler. Ari fills `{number}` and opens
         * `aridemo://circle/3` as `ACTION_VIEW` on this package; the manifest's
         * intent filter routes it to [MainActivity]. Nothing binds the service,
         * and the SDK reports `internal_error` if Ari invokes it instead.
         *
         * A placeholder takes a type whose values the declaration constrains —
         * `int`, `long`, `double`, `bool` or `enum` — because the value goes into
         * a uri another component then handles. A list never fills one: a set has
         * no meaning as a single uri component. A free-text `string` fills one
         * only when the tool declares it with `freeTextInUri` rather than
         * `string`, which is the SDK making the deliberate case say so out loud —
         * nothing in the declaration bounds that text, so whatever handles the
         * link must read it as untrusted input. That opt-in is how a tool takes a
         * phrase. Every tool that opens a screen is a deeplink; no result opens
         * one.
         *
         * The template is [CircleDeeplink.TEMPLATE], so what Ari opens and what
         * the app parses are one constant.
         *
         * `deeplink()` takes `confirm` too, and this leaves it off: highlighting a
         * circle changes nothing. A deeplink that committed something would want
         * it more than a bound tool does, because once Ari opens the link this
         * app's screen is all that stands between the model and the action.
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

    /**
     * The full-screen refusal is the one failure here worth a `fixWith`:
     * [REMOVE_CIRCLE] frees exactly the one slot this call wants, and at
     * [CircleState.MAX_CIRCLES] there is always a circle to remove, so the tool
     * Ari offers can always run.
     *
     * It does take a `number`, so the repair is a step rather than a tap. That is
     * as good as this registry gets — every removal tool takes an argument — and
     * the numbers are printed on the circles the user is looking at.
     */
    private fun addCircle(requested: String?): AriToolResult {
        val color = requested ?: CircleState.DEFAULT_COLOR
        if (CircleState.colorOf(color) == null) return unknownColor(color)

        val number = CircleState.add(color)
            ?: return AriToolResult.error(
                AriToolErrorCode.TEMPORARILY_UNAVAILABLE,
                "There are already ${CircleState.MAX_CIRCLES} circles, " +
                    "which is the maximum. Remove one first.",
                fixWith = REMOVE_CIRCLE,
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
     * `removed` is a count here and the circle's number in [removeCircle]'s
     * result. The tool name travels with the result, so the model reads this one
     * as the plural tool's answer.
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
            // Keyed by the permanent number, which the model reads back and uses
            // to map a colour onto one. putObject keeps it JSON, not prose.
            putObject("circles") {
                circles.forEach { circle -> putString("${circle.number}", circle.colorName) }
            }
        }
    }

    /**
     * Unreachable while the `color` enum's values and this resolver are the same
     * list. Kept as the backstop for a value Ari sends off-list.
     */
    private fun unknownColor(color: String) = AriToolResult.error(
        AriToolErrorCode.INVALID_ARGUMENT,
        "I don't know the colour '$color'. Try one of: " +
            CircleState.supportedNames().joinToString(", "),
    )

    /**
     * Numbers are permanent and can have gaps, so naming the ones that do exist
     * is what lets Ari and the user recover without another round trip.
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
