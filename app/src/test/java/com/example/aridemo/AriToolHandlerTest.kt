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
 * so each of these covers the permission gate, the argument parsing, the
 * handler and the error envelope over one code path. There is no second path a
 * test could prove instead: what passes here is what a real invocation does.
 *
 * The other test classes stop at the declaration — [AriToolServiceTest] reads
 * the registry, [AriToolsAssetTest] compares it to the committed asset, and
 * [CircleDeeplinkTest] checks the manifest against it. None reaches a handler,
 * so none would notice `add_circle` returning the wrong number.
 *
 * `show_circle` is the exception: it is a deeplink, so it has no handler to run
 * and the only thing to prove here is that the service refuses to run it.
 *
 * The `@OptIn` is needed because `Dispatchers.setMain`,
 * `UnconfinedTestDispatcher` and `Dispatchers.resetMain` are all marked
 * [ExperimentalCoroutinesApi] and this project sets no opt-in compiler flag.
 * Leaving it off is three warnings rather than a failed build, so it is hygiene
 * — but leviathan opts in globally and a partner project does not, which is why
 * the SDK's README shows the annotation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AriToolHandlerTest {

    /**
     * Each handler runs on the main dispatcher, which a JVM test has to supply.
     * `Unconfined` runs it on the calling thread, so the result is there by the
     * time `invokeToolInTest` returns.
     */
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

    /**
     * A full screen is a refusal, not a crash, and `unavailable` is the code
     * that tells Ari a retry can work once the user removes one.
     */
    @Test
    fun `add_circle past the limit reports unavailable`() {
        repeat(CircleState.MAX_CIRCLES - 1) { invoke("add_circle") }

        val failure = failure(invoke("add_circle"))

        assertEquals(AriToolErrorCode.UNAVAILABLE.wireValue, failure.code)
        assertTrue(failure.message, "${CircleState.MAX_CIRCLES}" in failure.message.orEmpty())
    }

    /**
     * The property the whole sample is built around: removing a circle leaves a
     * gap rather than renumbering, so a batch of removals from one
     * `list_circles` cannot hit the wrong circle.
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
     * `number` is declared required, so the handler reads it with the strict
     * accessor and the SDK turns a missing value into the coded envelope that
     * lets Ari ask the model again. The app writes no message for this.
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
     * The call the tool exists for: one invocation, one result, whatever the
     * match count. Two green circles go and the red one stays, and `total` is
     * what is left rather than what went.
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
     * No circle of that colour is a successful call that removed nothing, not a
     * failure: there is nothing for Ari to recover from and nothing to ask the
     * model to fix. `removed` 0 is the whole signal, which is why the tool's
     * description tells the model what 0 means.
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
     * `grey` and `gray` are two names for one colour in the palette, so the user
     * saying either has to reach the same circles. Matching on the stored name
     * instead of the resolved colour would pass every other test here and fail
     * this one, and on a headset it would look like the tool ignoring a circle
     * that is plainly grey.
     */
    @Test
    fun `remove_circles_by_color matches the colour, not the name it was added under`() {
        ok(invoke("add_circle", """{"color":"grey"}"""))

        val payload = ok(invoke("remove_circles_by_color", """{"color":"gray"}"""))

        assertEquals(1, payload.int("removed"))
        assertEquals(listOf(1), CircleState.activeNumbers())
    }

    /**
     * `color` is declared required and has no sensible default — a bulk removal
     * that guessed a colour would delete circles nobody named — so the strict
     * accessor is right and the SDK writes the message.
     */
    @Test
    fun `remove_circles_by_color without its required colour names the argument`() {
        val failure = failure(invoke("remove_circles_by_color", "{}"))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertEquals("arg 'color' is missing", failure.message)
    }

    /**
     * Same backstop as `set_circle_color`'s, over a second handler. The enum
     * constrains the model and not the wire, and this tool deletes rather than
     * recolours, so a colour it cannot resolve must refuse instead of matching
     * nothing and reporting a clean 0.
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
     * The declaration constrains the model, not the wire. Nothing in the SDK
     * checks a value against an `enum` arg's `values`, so `unknownColor` in
     * [AriToolService] is the backstop that answers an off-list colour — and it
     * is reachable, which is why it is still there.
     */
    @Test
    fun `a colour outside the declared list is the app's own refusal`() {
        val failure = failure(invoke("set_circle_color", """{"color":"cerulean"}"""))

        assertEquals(AriToolErrorCode.INVALID_ARGUMENT.wireValue, failure.code)
        assertTrue(failure.message, "cerulean" in failure.message.orEmpty())
    }

    /**
     * How Ari answers "what's on screen?" — it keys the colours by number.
     *
     * `circles` is a nested object, and `ToolArgs` reads flat values only: an
     * object is never text, so `payload.string("circles")` throws. Read a
     * nested payload through `org.json` instead, off `payload.toString()`.
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
     * A name this app never declared. The SDK answers it from the registry, so
     * a stale tool in Ari's view cannot reach a handler that no longer exists.
     */
    @Test
    fun `a tool this app never declared reports unknown_tool`() {
        val failure = failure(invoke("take_note"))

        assertEquals(AriToolErrorCode.UNKNOWN_TOOL.wireValue, failure.code)
    }

    /**
     * `show_circle` is declared with a `uri` and no handler, so Ari is meant to
     * open the link rather than bind this service. Invoking it is Ari's mistake,
     * not the model's, and the SDK says so rather than reporting a missing tool
     * — `show_circle` exists, it just runs no code here.
     *
     * This is the whole of what a JVM test can say about the deeplink path
     * through the service: the screen it actually opens needs a device.
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
