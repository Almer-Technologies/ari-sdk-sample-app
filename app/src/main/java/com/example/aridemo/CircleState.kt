package com.example.aridemo

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * The circles on screen, shared between [MainActivity] and [AriToolService].
 *
 * **Numbers are stable for the life of a circle.** Removing circle 3 leaves a
 * gap; the rest keep their numbers. That is what makes a batch of removals safe:
 * the model issues one `remove_circle` per circle in parallel, often before the
 * first result comes back, so positional numbering would send every removal
 * after the first at the wrong circle.
 *
 * Process-wide singleton because the tool service and the UI are separate
 * components in the same process. A production app would inject a repository
 * into both and persist it — see the README.
 */
object CircleState {

    /** Most circles that fit legibly on a headset screen. Caps count, not numbers. */
    const val MAX_CIRCLES = 6

    /** Colour [add] uses when Ari sends none. Named in `add_circle`'s declaration. */
    const val DEFAULT_COLOR = "red"

    /**
     * Colours Ari may pick, and the one source for both halves of that:
     * [supportedNames] feeds the `values` of every `color` arg in
     * `AriToolService`'s declaration, and this same map resolves the name Ari
     * sends back. Insertion order keeps the generated asset stable.
     *
     * `grey` and `gray` map to one colour on purpose — speech-to-text produces
     * either — which is why [removeByColor] matches on the resolved colour.
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

    val count: Int get() = _circles.value.size

    /** The numbers currently in use, in display order — for error messages. */
    fun activeNumbers(): List<Int> = _circles.value.map { it.number }

    /** Colour names this app accepts, in declaration order. */
    fun supportedNames(): List<String> = NAMED.keys.toList()

    fun colorOf(name: String): Color? = NAMED[name.lowercase().trim()]

    /**
     * Black or white, whichever stays readable on [background]. The palette
     * includes white and yellow, on which a fixed white number would disappear.
     */
    fun contrastingTextColor(background: Color): Color =
        if (background.luminance() > 0.5f) Color.Black else Color.White

    /**
     * Back to the one red circle a fresh process starts with. Exists only because
     * this state is a singleton and JUnit runs every test in one process; an
     * injected repository would get a fresh instance per test.
     */
    fun reset() {
        nextNumber.set(2)
        _circles.value = listOf(Circle(1, DEFAULT_COLOR))
    }

    /**
     * Append a circle with a fresh, never-reused number.
     *
     * @return The number assigned to it, or `null` when already at [MAX_CIRCLES].
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
     * @return `true` when removed, `false` when there is no such circle.
     */
    fun remove(number: Int): Boolean {
        val remaining = _circles.value.filterNot { it.number == number }
        if (remaining.size == count) return false
        _circles.value = remaining
        return true
    }

    /**
     * Remove every circle of one colour, matched on the colour a name **resolves
     * to** rather than the name it was added under — see [NAMED].
     *
     * @return How many circles were removed, or `null` when [colorName] is not a
     *   name this app knows. Zero is a removal of nothing, not a failure.
     */
    fun removeByColor(colorName: String): Int? {
        val target = colorOf(colorName) ?: return null
        val (matched, remaining) = _circles.value.partition { colorOf(it.colorName) == target }
        if (matched.isEmpty()) return 0
        _circles.value = remaining
        return matched.size
    }

    /**
     * Recolour one circle, or every circle when [number] is null.
     *
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
