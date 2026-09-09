package com.example.aridemo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The circles on screen, shared between [MainActivity] and [AriToolService].
 *
 * **Numbers are stable for the life of a circle.** Circle 3 stays circle 3 until
 * it is removed, and removing it leaves a gap — the remaining circles keep their
 * numbers rather than shifting down.
 *
 * That matters more than it looks. "Remove the purple ones" is one call to
 * `remove_circles_by_color` now, but a batch of `remove_circle` calls has not
 * gone away — the model still issues one per circle when the user names two of
 * them, and it issues them in parallel, often *before* the first result comes
 * back. With positional numbering, every removal after the first would target a
 * number that had already shifted, and the wrong circle would go. Stable
 * numbers make a batch of removals safe by construction.
 *
 * Process-wide singleton because the tool service and the UI are separate
 * components in the same process. A production app would hold this in a
 * repository injected into both, and persist it — see the README.
 */
object CircleState {

    /** Most circles that fit legibly on a headset screen. Caps count, not numbers. */
    const val MAX_CIRCLES = 6

    /** Colour [add] uses when Ari sends none. Named in `add_circle`'s declaration. */
    const val DEFAULT_COLOR = "red"

    /**
     * Colours Ari may pick.
     *
     * This map is the single source of both halves that used to be kept in step
     * by hand. [supportedNames] feeds the `values` of EVERY `color` arg in
     * `AriToolService`'s declaration, and the same map resolves the name Ari
     * sends back. Adding an entry here therefore reaches the model as soon as
     * the declaration asset is regenerated, and a colour can no longer be
     * offered but unresolvable, or resolvable but unreachable.
     *
     * Iteration order is insertion order, so the generated asset is stable.
     *
     * `grey` and `gray` both map to the same colour on purpose: speech-to-text
     * will produce either, and the model can only pick from this list. That is
     * also why [removeByColor] matches on the colour a name resolves to rather
     * than on the name itself — otherwise the two would reach different
     * circles.
     */
    private val NAMED = mapOf(
        "red" to Color.Red,
        "green" to Color(0xFF00A000),
        "blue" to Color.Blue,
        "yellow" to Color.Yellow,
        "purple" to Color(0xFF800080),
        "orange" to Color(0xFFFF8000),
        "pink" to Color(0xFFFF69B4),
        "cyan" to Color(0xFF00BCD4),
        "brown" to Color(0xFF8B4513),
        "black" to Color.Black,
        "white" to Color.White,
        "grey" to Color(0xFF9E9E9E),
        "gray" to Color(0xFF9E9E9E),
    )

    /** One circle. [number] is assigned once and never changes. */
    data class Circle(val number: Int, val colorName: String)

    private val nextNumber = AtomicInteger(2)
    private val _circles = MutableStateFlow(listOf(Circle(1, DEFAULT_COLOR)))

    /** Circles in display order. Numbers are stable and may have gaps. */
    val circles: StateFlow<List<Circle>> = _circles

    /** How many circles are on screen. */
    val count: Int get() = _circles.value.size

    /** The numbers currently in use, in display order — for error messages. */
    fun activeNumbers(): List<Int> = _circles.value.map { it.number }

    /**
     * Colour names this app accepts, in declaration order.
     *
     * Read twice: once by `AriToolService` as the `values` of each `color`
     * enum arg, and once for error messages.
     */
    fun supportedNames(): List<String> = NAMED.keys.toList()

    /** Resolve a declared colour name, or `null` if it isn't one we know. */
    fun colorOf(name: String): Color? = NAMED[name.lowercase().trim()]

    /**
     * Black or white, whichever stays readable on [background].
     *
     * Needed once the palette includes white and yellow: the circle's number is
     * drawn on top of it, and a fixed white would disappear on both.
     */
    fun contrastingTextColor(background: Color): Color =
        if (background.luminance() > 0.5f) Color.Black else Color.White

    /**
     * Back to the one red circle a fresh process starts with.
     *
     * This exists because the state is a process-wide singleton and JUnit runs
     * every test in one process, so without it the first test's circles would
     * decide what the second test sees. A production app holding this in a
     * repository injected into the service and the UI would get a fresh
     * instance per test and need nothing like this — see the README.
     */
    fun reset() {
        nextNumber.set(2)
        _circles.value = listOf(Circle(1, DEFAULT_COLOR))
    }

    /**
     * Append a circle with a fresh, never-reused number.
     *
     * @param colorName Colour for the new circle; must be a known name.
     * @return The number assigned to it, or `null` when already at
     *   [MAX_CIRCLES].
     */
    fun add(colorName: String): Int? {
        if (count >= MAX_CIRCLES) return null
        val number = nextNumber.getAndIncrement()
        _circles.value = _circles.value + Circle(number, colorName.lowercase().trim())
        return number
    }

    /**
     * Remove one circle by number. Remaining circles keep their numbers.
     *
     * @param number The circle's stable number.
     * @return `true` when removed, `false` when there is no such circle.
     */
    fun remove(number: Int): Boolean {
        val remaining = _circles.value.filterNot { it.number == number }
        if (remaining.size == count) return false
        _circles.value = remaining
        return true
    }

    /**
     * Remove every circle of one colour. Remaining circles keep their numbers.
     *
     * Circles are matched on the colour a name **resolves to**, not on the name
     * they were added under. [NAMED] holds two names for one colour — `grey`
     * and `gray` — so matching on the stored name would leave a circle added as
     * `grey` untouched by "remove the gray ones", and neither the user nor the
     * model can hear which of the two the other used.
     *
     * Shaped like [setColor]: a count, or `null` for an argument this app cannot
     * make sense of.
     *
     * @param colorName Colour to remove; must be a known name.
     * @return How many circles were removed, or `null` when [colorName] is not
     *   one this app knows. Zero means no circle had that colour, which is a
     *   removal of nothing rather than a failure.
     */
    fun removeByColor(colorName: String): Int? {
        val target = colorOf(colorName) ?: return null
        val (matched, remaining) = _circles.value.partition { colorOf(it.colorName) == target }
        if (matched.isEmpty()) return 0
        _circles.value = remaining
        return matched.size
    }

    /**
     * Recolour one circle, or every circle.
     *
     * @param colorName Colour to apply; must be a known name.
     * @param number The circle's stable number, or `null` for all of them.
     * @return How many circles changed, or `null` when [number] names a circle
     *   that does not exist.
     */
    fun setColor(colorName: String, number: Int?): Int? {
        val name = colorName.lowercase().trim()
        if (number == null) {
            val changed = count
            _circles.value = _circles.value.map { it.copy(colorName = name) }
            return changed
        }
        if (_circles.value.none { it.number == number }) return null
        _circles.value = _circles.value.map {
            if (it.number == number) it.copy(colorName = name) else it
        }
        return 1
    }
}
