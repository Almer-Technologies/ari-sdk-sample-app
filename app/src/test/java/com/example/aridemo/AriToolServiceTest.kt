package com.example.aridemo

import com.ari_os.ari.sdk.declaration.AriToolArg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry [AriToolService] exposes.
 *
 * A build that compiles but declares nothing is the bug this guards: Ari offers
 * the model exactly what `tools()` returns, so an empty registry is an app with
 * no tools rather than a broken one, and nothing else would fail.
 *
 * Read through the service rather than off [DemoTools] on purpose. The build
 * generates the asset from the object, so what is left to pin is the other half:
 * that `tools()` hands Ari that same registry and not another one.
 *
 * Only what is true of THIS app is asserted here. Every declaration-shape rule —
 * the name pattern, the description cap, a repeated tool name, a `tool()` with no
 * `handle { }`, a template that misses a required arg or fills a placeholder with
 * free text — is enforced by `ariTools { }` as the registry is built, and covered
 * by the SDK's own suite. Constructing the field above therefore runs all of it,
 * and restating any of it here would only pin the SDK's behaviour twice.
 */
class AriToolServiceTest {

    private val registry = AriToolService().tools()

    @Test
    fun `the service declares the six circle tools, in order`() {
        assertEquals(
            listOf(
                "add_circle",
                "remove_circle",
                "remove_circles_by_color",
                "set_circle_color",
                "list_circles",
                "show_circle",
            ),
            registry.declarations.map { tool -> tool.name },
        )
    }

    /**
     * Whether the model picks the right one of the two needs a headset; what a
     * test can hold is that each description still names the other, so neither can
     * be reworded into claiming the other's case.
     *
     * Structural rather than phrase-matching on purpose — rewording is normal,
     * dropping the pointer is the regression. The one phrase pinned is the zero
     * case, which Ari would otherwise narrate as a removal.
     */
    @Test
    fun `each removal tool's description points at the other, and names the zero case`() {
        val one = registry.declarations.single { tool -> tool.name == "remove_circle" }
        val many = registry.declarations.single { tool -> tool.name == "remove_circles_by_color" }

        assertTrue(one.description, "remove_circles_by_color" in one.description)
        assertTrue(many.description, "remove_circle" in many.description)
        assertTrue(many.description, "0 means" in many.description)
    }

    /**
     * `uri` is the one field that decides which path Ari takes, and getting it
     * wrong fails nowhere at runtime — Ari simply takes the other path.
     */
    @Test
    fun `only show_circle is a deeplink, and the rest are invoked`() {
        val (deeplinks, invocable) = registry.declarations.partition { tool -> tool.uri != null }

        assertEquals(listOf("show_circle"), deeplinks.map { tool -> tool.name })
        assertEquals(
            listOf(
                "add_circle",
                "remove_circle",
                "remove_circles_by_color",
                "set_circle_color",
                "list_circles",
            ),
            invocable.map { tool -> tool.name },
        )
    }

    /**
     * The cloud reads `confirm`, so this pins behaviour the user feels. Both
     * halves matter: a flag added to a harmless tool trains the user to wave
     * prompts through, and one dropped from a removal takes the prompt away
     * silently, since `false` is the default. Asserting the whole set — rather
     * than that each removal has it — is what catches the second.
     */
    @Test
    fun `the two removal tools are the only ones that declare confirm`() {
        assertEquals(
            listOf("remove_circle", "remove_circles_by_color"),
            registry.declarations.filter { tool -> tool.confirm }.map { tool -> tool.name },
        )
    }

    /**
     * The point of declaring in code: every `color` arg takes its values from the
     * palette that resolves them. The count is asserted rather than inferred,
     * because a fourth tool taking a colour is the change that could bring a
     * hand-written list with it.
     */
    @Test
    fun `every color arg offers exactly the palette CircleState resolves`() {
        val colorArgs = registry.declarations
            .flatMap { tool -> tool.args }
            .filterIsInstance<AriToolArg.EnumArg>()
            .filter { arg -> arg.name == "color" }

        assertEquals(3, colorArgs.size)
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
