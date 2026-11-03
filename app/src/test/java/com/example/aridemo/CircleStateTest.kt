package com.example.aridemo

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The state rules the tools rely on, checked on [CircleState] directly.
 *
 * [AriToolHandlerTest] reaches most of these through a tool call, but a tool
 * only ever passes a name it has already resolved. What [CircleState] does with
 * anything else — and what it promises its two callers about numbers — is
 * pinned here.
 */
class CircleStateTest {

    /** A singleton, so every test starts from the one circle a fresh process has. */
    @Before
    fun setUp() {
        CircleState.reset()
    }

    /**
     * The model may still hold a number from an earlier `list_circles`. Reusing
     * a freed number would point that stale number at a circle it never meant.
     */
    @Test
    fun `a removed number is never handed out again`() {
        assertEquals(2, CircleState.add("blue"))
        CircleState.remove(2)

        assertEquals(3, CircleState.add("blue"))
        assertEquals(listOf(1, 3), CircleState.activeNumbers())
    }

    @Test
    fun `add refuses at the limit, changes nothing, and works again after a removal`() {
        repeat(CircleState.MAX_CIRCLES - 1) { CircleState.add("blue") }
        val full = CircleState.circles.value

        assertNull(CircleState.add("green"))
        assertEquals(full, CircleState.circles.value)

        CircleState.remove(1)
        assertEquals(CircleState.MAX_CIRCLES + 1, CircleState.add("green"))
    }

    @Test
    fun `remove of a number that is not there reports false and changes nothing`() {
        assertFalse(CircleState.remove(7))
        assertEquals(listOf(1), CircleState.activeNumbers())
    }

    /** Null, not zero: the tool turns null into a refusal and zero into a success. */
    @Test
    fun `removeByColor of a name the palette does not know reports null`() {
        assertNull(CircleState.removeByColor("cerulean"))
        assertEquals(listOf(1), CircleState.activeNumbers())
    }

    @Test
    fun `removeByColor with no circle of that colour removes nothing`() {
        val before = CircleState.circles.value

        assertEquals(0, CircleState.removeByColor("green"))
        assertEquals(before, CircleState.circles.value)
    }

    @Test
    fun `removeByColor reaches grey circles through gray and gray circles through grey`() {
        CircleState.add("grey")
        CircleState.add("gray")

        assertEquals(2, CircleState.removeByColor("gray"))
        assertEquals(listOf(1), CircleState.activeNumbers())

        CircleState.add("gray")
        assertEquals(1, CircleState.removeByColor("grey"))
        assertEquals(listOf(1), CircleState.activeNumbers())
    }

    /**
     * [CircleState.add] stores whatever name it is given; only the tools check
     * it. A circle under a name the palette cannot resolve matches no colour, so
     * a bulk removal never takes it by accident.
     */
    @Test
    fun `a circle stored under an unknown name is never matched by removeByColor`() {
        CircleState.add("cerulean")

        CircleState.supportedNames().forEach { name ->
            CircleState.removeByColor(name)
        }

        assertEquals(listOf(2), CircleState.activeNumbers())
    }

    @Test
    fun `setColor with no number recolours every circle and counts them`() {
        CircleState.add("green")
        CircleState.add("blue")

        assertEquals(3, CircleState.setColor("pink", null))
        assertEquals(
            listOf("pink", "pink", "pink"),
            CircleState.circles.value.map { circle -> circle.colorName },
        )
    }

    @Test
    fun `setColor with a number recolours only that circle`() {
        CircleState.add("green")
        CircleState.add("blue")

        assertEquals(1, CircleState.setColor("pink", 2))
        assertEquals(
            listOf("red", "pink", "blue"),
            CircleState.circles.value.map { circle -> circle.colorName },
        )
    }

    @Test
    fun `setColor of a number that is not there reports null and changes nothing`() {
        val before = CircleState.circles.value

        assertNull(CircleState.setColor("pink", 7))
        assertEquals(before, CircleState.circles.value)
    }

    @Test
    fun `setColor of a number on an empty screen reports null`() {
        CircleState.remove(1)

        assertNull(CircleState.setColor("pink", 1))
        assertEquals(emptyList<CircleState.Circle>(), CircleState.circles.value)
    }

    /** Speech-to-text capitalises and pads; a colour Ari heard must still resolve. */
    @Test
    fun `colorOf ignores case and surrounding spaces`() {
        assertEquals(Color.Blue, CircleState.colorOf("  Blue "))
        assertEquals(Color.Blue, CircleState.colorOf("BLUE"))
        assertNull(CircleState.colorOf("cerulean"))
    }

    /** `list_circles` reports the stored name, so it is stored the way it is offered. */
    @Test
    fun `add stores the colour name lower-cased and trimmed`() {
        CircleState.add("  Blue ")

        assertEquals("blue", CircleState.circles.value.last().colorName)
    }

    /** A fixed white number disappears on the palette's light colours. */
    @Test
    fun `the number is black on light colours and white on dark ones`() {
        assertEquals(Color.Black, CircleState.contrastingTextColor(Color.White))
        assertEquals(Color.Black, CircleState.contrastingTextColor(Color.Yellow))
        assertEquals(Color.White, CircleState.contrastingTextColor(Color.Blue))
        assertEquals(Color.White, CircleState.contrastingTextColor(Color.Black))
    }
}
