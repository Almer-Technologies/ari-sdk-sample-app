package com.example.aridemo

import com.ari_os.ari.sdk.AriToolErrorCode
import com.ari_os.ari.sdk.AriToolResult
import com.ari_os.ari.sdk.invokeToolInTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every tool this app declares, run end to end.
 *
 * `invokeToolInTest` enters [AriToolService] through the same binder Ari calls,
 * so each of these covers the permission gate, the argument parsing, the handler
 * and the error envelope over one code path. The other test classes stop at the
 * declaration — [AriToolServiceTest] reads the registry, [AriToolsAssetTest]
 * compares it to the committed asset, [CircleDeeplinkTest] checks the manifest
 * against it — so none would notice `add_circle` returning the wrong number.
 *
 * The `@OptIn` covers `Dispatchers.setMain`/`resetMain` and
 * `UnconfinedTestDispatcher`, all [ExperimentalCoroutinesApi], since this
 * project sets no global opt-in flag the way leviathan does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AriToolHandlerTest {

    /** Handlers run on the main dispatcher; `Unconfined` runs them inline. */
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        CircleState.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `add_circle reports the number, the colour and the new total`() {
        val payload = ok(invoke("add_circle", """{"color":"blue"}"""))

        assertEquals(2, payload.int("number"))
        assertEquals("blue", payload.string("color"))
        assertEquals(2, payload.int("total"))
    }

    /** The default the declaration promises. Ari sends no `color` at all here. */
    @Test
    fun `add_circle with no colour uses the default the declaration names`() {
        val payload = ok(invoke("add_circle"))

        assertEquals(CircleState.DEFAULT_COLOR, payload.string("color"))
    }

    /** `unavailable` is the code that tells Ari a retry works once one is removed. */
    @Test
    fun `add_circle past the limit reports unavailable`() {
        repeat(CircleState.MAX_CIRCLES - 1) { invoke("add_circle") }

        val failure = failure(invoke("add_circle"))

        assertEquals(AriToolErrorCode.UNAVAILABLE.wireValue, failure.code)
        assertTrue(failure.message, "${CircleState.MAX_CIRCLES}" in failure.message.orEmpty())
    }

    /**
     * The property the whole sample is built around: a removal leaves a gap rather
     * than renumbering, so a batch of removals cannot hit the wrong circle.
     */
    @Test
    fun `remove_circle leaves the other numbers where they were`() {
        ok(invoke("add_circle", """{"color":"green"}"""))
        ok(invoke("add_circle", """{"color":"blue"}"""))

        val removed = ok(invoke("remove_circle", """{"number":2}"""))

        assertEquals(2, removed.int("removed"))
        assertEquals(2, removed.int("total"))
        assertEquals(listOf(1, 3), CircleState.activeNumbers())
    }

    /**
     * `number` is required, so the SDK turns a missing value into the coded
     * envelope that lets Ari ask the model again. The app writes no message.
     */
    @Test
    fun `remove_circle without its required argument names the argument`() {
        val failure = failure(invoke("remove_circle", "{}"))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertEquals("arg 'number' is missing", failure.message)
    }

    /** Numbers have gaps, so "no such circle" has to say which ones there are. */
    @Test
    fun `remove_circle of a number that is not there names the ones that are`() {
        val failure = failure(invoke("remove_circle", """{"number":7}"""))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertEquals("There's no circle 7. The circles are 1.", failure.message)
    }

    /**
     * One invocation, one result, whatever the match count. `total` is what is
     * left rather than what went.
     */
    @Test
    fun `remove_circles_by_color removes every circle of that colour in one call`() {
        ok(invoke("add_circle", """{"color":"green"}"""))
        ok(invoke("add_circle", """{"color":"green"}"""))
        ok(invoke("add_circle", """{"color":"blue"}"""))

        val payload = ok(invoke("remove_circles_by_color", """{"color":"green"}"""))

        assertEquals("green", payload.string("color"))
        assertEquals(2, payload.int("removed"))
        assertEquals(2, payload.int("total"))
        assertEquals(listOf(1, 4), CircleState.activeNumbers())
    }

    /**
     * A call that removed nothing is a success, not a failure: nothing for Ari to
     * recover from. `removed` 0 is the whole signal, which is why the description
     * tells the model what 0 means.
     */
    @Test
    fun `remove_circles_by_color removes nothing when no circle has that colour`() {
        ok(invoke("add_circle", """{"color":"blue"}"""))

        val payload = ok(invoke("remove_circles_by_color", """{"color":"green"}"""))

        assertEquals("green", payload.string("color"))
        assertEquals(0, payload.int("removed"))
        assertEquals(2, payload.int("total"))
        assertEquals(listOf(1, 2), CircleState.activeNumbers())
    }

    /**
     * `grey` and `gray` are two names for one colour, so either has to reach the
     * same circles. Matching on the stored name passes every other test here and
     * fails this one.
     */
    @Test
    fun `remove_circles_by_color matches the colour, not the name it was added under`() {
        ok(invoke("add_circle", """{"color":"grey"}"""))

        val payload = ok(invoke("remove_circles_by_color", """{"color":"gray"}"""))

        assertEquals(1, payload.int("removed"))
        assertEquals(listOf(1), CircleState.activeNumbers())
    }

    /**
     * `color` is required and has no sensible default: a bulk removal that guessed
     * would delete circles nobody named.
     */
    @Test
    fun `remove_circles_by_color without its required colour names the argument`() {
        val failure = failure(invoke("remove_circles_by_color", "{}"))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertEquals("arg 'color' is missing", failure.message)
    }

    /**
     * The enum constrains the model, not the wire, and this tool deletes, so an
     * unresolvable colour must refuse rather than match nothing and report a
     * clean 0.
     */
    @Test
    fun `remove_circles_by_color refuses a colour outside the declared list`() {
        ok(invoke("add_circle", """{"color":"green"}"""))

        val failure = failure(invoke("remove_circles_by_color", """{"color":"cerulean"}"""))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertTrue(failure.message, "cerulean" in failure.message.orEmpty())
        assertEquals(2, CircleState.count)
    }

    @Test
    fun `set_circle_color with no number recolours every circle`() {
        ok(invoke("add_circle", """{"color":"green"}"""))
        ok(invoke("add_circle", """{"color":"blue"}"""))

        val payload = ok(invoke("set_circle_color", """{"color":"pink"}"""))

        assertEquals(3, payload.int("changed"))
        assertEquals(
            listOf("pink", "pink", "pink"),
            CircleState.circles.value.map { circle -> circle.colorName },
        )
    }

    /**
     * Nothing in the SDK checks a value against an `enum` arg's `values`, so
     * `unknownColor` in [AriToolService] is a reachable backstop, not dead code.
     */
    @Test
    fun `a colour outside the declared list is the app's own refusal`() {
        val failure = failure(invoke("set_circle_color", """{"color":"cerulean"}"""))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertTrue(failure.message, "cerulean" in failure.message.orEmpty())
    }

    /**
     * `ToolArgs` reads flat values only, so `payload.string("circles")` throws on
     * the nested object. Read a nested payload through `org.json`, off
     * `payload.toString()`.
     */
    @Test
    fun `list_circles reports every circle keyed by its permanent number`() {
        ok(invoke("add_circle", """{"color":"green"}"""))
        ok(invoke("remove_circle", """{"number":1}"""))

        val payload = ok(invoke("list_circles"))

        assertEquals(1, payload.int("total"))
        val circles = JSONObject(payload.toString()).getJSONObject("circles")
        assertEquals(1, circles.length())
        assertEquals("green", circles.getString("2"))
    }

    /**
     * The SDK answers from the registry, so a stale tool in Ari's view cannot
     * reach a handler that no longer exists.
     */
    @Test
    fun `a tool this app never declared reports unknown_tool`() {
        val failure = failure(invoke("take_note"))

        assertEquals(AriToolErrorCode.UNKNOWN_TOOL.wireValue, failure.code)
    }

    /**
     * Invoking a deeplink tool is Ari's mistake, not the model's, so the SDK says
     * `app_error` rather than reporting a missing tool. This is all a JVM test can
     * say about the deeplink path — the screen it opens needs a device.
     */
    @Test
    fun `invoking the deeplink tool reports app_error rather than running anything`() {
        val failure = failure(invoke("show_circle", """{"number":1}"""))

        assertEquals(AriToolErrorCode.APP_ERROR.wireValue, failure.code)
        assertTrue(failure.message, "deeplink" in failure.message.orEmpty())
    }

    private fun invoke(toolName: String, argsJson: String = ""): AriToolResult =
        AriToolService().invokeToolInTest(toolName, argsJson)

    private fun ok(result: AriToolResult) = (result as AriToolResult.Ok).payload

    private fun failure(result: AriToolResult) = result as AriToolResult.Failure
}
