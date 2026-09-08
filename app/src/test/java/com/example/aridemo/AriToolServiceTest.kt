package com.example.aridemo

import com.ari_os.ari.sdk.AriToolArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry [AriToolService] exposes.
 *
 * A build that compiles but declares nothing is the bug this guards: Ari offers
 * the model exactly what `tools()` returns, so an empty registry is an app with
 * no tools rather than a broken one, and nothing else would fail.
 *
 * Constructing the registry is itself a check. `ariTools { }` rejects a `tool()`
 * with no `handle { }` block, a repeated name, a name outside the contract's
 * pattern, a description over the cap and a ninth tool. So these assertions run
 * only if every one of those already held.
 */
class AriToolServiceTest {

    private val registry = AriToolService().tools()

    @Test
    fun `the service declares the four circle tools, in order`() {
        assertEquals(
            listOf("add_circle", "remove_circle", "set_circle_color", "list_circles"),
            registry.declarations.map { tool -> tool.name },
        )
    }

    /** Every tool here is one Ari runs by binding the service, so none may be a deeplink. */
    @Test
    fun `every declared tool is invocable rather than a deeplink`() {
        registry.declarations.forEach { tool ->
            assertNull("tool '${tool.name}' declares a uri", tool.uri)
            assertTrue("tool '${tool.name}' presents ui", !tool.presentsUi)
        }
    }

    /** Removal cannot be undone, so it is the one tool Ari must confirm first. */
    @Test
    fun `only removal asks the user to confirm`() {
        assertEquals(
            listOf("remove_circle"),
            registry.declarations.filter { tool -> tool.confirm }.map { tool -> tool.name },
        )
    }

    /**
     * The point of declaring in code: both `color` args take their allowed
     * values from the palette that resolves them, so the two cannot disagree.
     */
    @Test
    fun `both color args offer exactly the palette CircleState resolves`() {
        val colorArgs = registry.declarations
            .flatMap { tool -> tool.args }
            .filterIsInstance<AriToolArg.EnumArg>()
            .filter { arg -> arg.name == "color" }

        assertEquals(2, colorArgs.size)
        colorArgs.forEach { arg ->
            assertEquals(CircleState.supportedNames(), arg.values)
            arg.values.forEach { value ->
                assertTrue("'$value' is offered but does not resolve", CircleState.colorOf(value) != null)
            }
        }
    }

    @Test
    fun `the default color add_circle names is one it can resolve`() {
        assertTrue(CircleState.colorOf(CircleState.DEFAULT_COLOR) != null)
        assertTrue(CircleState.DEFAULT_COLOR in CircleState.supportedNames())
    }
}
