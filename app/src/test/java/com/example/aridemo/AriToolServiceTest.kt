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
 * pattern, a description over the cap and a ninth tool. It also rejects a
 * `deeplink()` whose template names an arg the tool does not declare, leaves out
 * a required one, or fills a placeholder with free text. So these assertions run
 * only if every one of those already held.
 */
class AriToolServiceTest {

    private val registry = AriToolService().tools()

    @Test
    fun `the service declares the five circle tools, in order`() {
        assertEquals(
            listOf(
                "add_circle",
                "remove_circle",
                "set_circle_color",
                "list_circles",
                "show_circle",
            ),
            registry.declarations.map { tool -> tool.name },
        )
    }

    /**
     * The two kinds of tool, told apart by the one field that decides which path
     * Ari takes. A `uri` means Ari opens the link itself; anything else means it
     * binds this service. Getting that wrong on a tool does not fail anywhere at
     * runtime — Ari simply takes the other path.
     */
    @Test
    fun `only show_circle is a deeplink, and the rest are invoked`() {
        val (deeplinks, invocable) = registry.declarations.partition { tool -> tool.uri != null }

        assertEquals(listOf("show_circle"), deeplinks.map { tool -> tool.name })
        assertEquals(
            listOf("add_circle", "remove_circle", "set_circle_color", "list_circles"),
            invocable.map { tool -> tool.name },
        )
    }

    /** A tool that opens a screen returns no data, so the SDK sets this for it. */
    @Test
    fun `the deeplink presents ui and nothing else does`() {
        assertEquals(
            listOf("show_circle"),
            registry.declarations.filter { tool -> tool.presentsUi }.map { tool -> tool.name },
        )
    }

    /**
     * The rule the SDK enforces as the registry is built, pinned here so it is
     * visible in this app rather than only upstream: Ari substitutes the value
     * into a uri another component then handles, so a free-text `string` cannot
     * fill a placeholder. `number` is an `int`, which can.
     */
    @Test
    fun `every placeholder in the deeplink names a constrained arg of that tool`() {
        val tool = registry.declarations.single { declaration -> declaration.uri != null }
        val placeholders = Regex("""\{([^{}]*)}""")
            .findAll(tool.uri.orEmpty())
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(listOf("number"), placeholders)
        placeholders.forEach { name ->
            val arg = tool.args.single { declared -> declared.name == name }
            assertTrue(
                "arg '$name' is ${arg::class.simpleName}, which cannot fill a placeholder",
                arg is AriToolArg.IntArg ||
                    arg is AriToolArg.NumberArg ||
                    arg is AriToolArg.BoolArg ||
                    arg is AriToolArg.EnumArg,
            )
        }
    }

    /** A required arg the template left out could never be delivered. */
    @Test
    fun `the deeplink template carries every required arg of its tool`() {
        val tool = registry.declarations.single { declaration -> declaration.uri != null }

        tool.args.filter { arg -> arg.required }.forEach { arg ->
            assertTrue(
                "required arg '${arg.name}' is not in ${tool.uri}",
                "{${arg.name}}" in tool.uri.orEmpty(),
            )
        }
    }

    /** Every tool Ari runs by binding this service must have somewhere to run. */
    @Test
    fun `no invocable tool declares a uri`() {
        registry.declarations
            .filter { tool -> tool.name != "show_circle" }
            .forEach { tool -> assertNull("tool '${tool.name}' declares a uri", tool.uri) }
    }

    /**
     * Ari confirms every tool regardless, so this pins intent rather than
     * behaviour: `confirm` is not read today, and removal is the one tool that
     * would want it if it were.
     */
    @Test
    fun `removal is the only tool that declares confirm`() {
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
