package com.ari_os.ari.sdk

import android.app.PendingIntent
import android.os.IBinder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AriToolProviderServiceTest {

    private class RegistryService(private val declare: AriToolsBuilder.() -> Unit) :
        AriToolProviderService() {
        override fun tools(): AriToolRegistry = ariTools(build = declare)
    }

    private class FailingRegistryService : AriToolProviderService() {
        override fun tools(): AriToolRegistry = error("tool store is closed")
    }

    private class RecordingCallback : IAriToolCallback {
        var calls = 0
        var requestId: String? = null
        var resultJson: String? = null
        var launchIntent: PendingIntent? = null

        override fun onResult(
            requestId: String,
            resultJson: String,
            launchIntent: PendingIntent?,
        ) {
            calls++
            this.requestId = requestId
            this.resultJson = resultJson
            this.launchIntent = launchIntent
        }

        override fun asBinder(): IBinder? = null
    }

    private class PermissionRecordingService : AriToolProviderService() {
        val gated = mutableListOf<String>()
        val steps = mutableListOf<String>()
        private var current = "?"

        override fun enforceAriPermission() {
            gated += current
            steps += "permission"
        }

        override fun tools(): AriToolRegistry = ariTools {
            tool(TOOL, DESCRIPTION) {
                handle {
                    steps += "handler"
                    AriToolResult.ok()
                }
            }
        }

        fun callInvoke() {
            current = "invoke"
            (onBind(null) as IAriToolProvider).invoke("req-1", TOOL, "", RecordingCallback())
        }

        fun callCancel() {
            current = "cancel"
            (onBind(null) as IAriToolProvider).cancel("req-1")
        }

        fun callHarness() {
            current = "harness"
            invokeToolInTest(TOOL)
        }
    }

    private class AvailabilityService(
        private val available: Set<String>?,
        private val failWith: Throwable? = null,
    ) : AriToolProviderService() {
        val pushed = mutableListOf<Set<String>>()

        override fun tools(): AriToolRegistry = ariTools {
            tool(TOOL, DESCRIPTION) { handle { AriToolResult.ok() } }
        }

        override fun availableTools(): Set<String>? = available

        override suspend fun sendAvailability(names: Set<String>): AriAvailabilityResult {
            failWith?.let { throw it }
            pushed += names
            return AriAvailabilityResult.Accepted
        }
    }

    private val uncaught = mutableListOf<Throwable>()
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // A throwable that escapes scope.launch reaches the process handler,
        // which on a device ends the app. Collect it instead of printing it.
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught += throwable }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        Dispatchers.resetMain()
    }

    private fun serviceReturning(result: AriToolResult) = RegistryService {
        tool(TOOL, DESCRIPTION) { handle { result } }
    }

    private fun serviceThrowing(throwable: Throwable) = RegistryService {
        tool(TOOL, DESCRIPTION) { handle { throw throwable } }
    }

    // The envelope keys cost bytes too, so the blob is what is left of the target size.
    private fun resultOfExactly(bytes: Int): AriToolResult {
        val envelope = AriToolResult.ok { putString(BLOB, "") }.toJson().utf8Bytes()
        return AriToolResult.ok { putString(BLOB, "x".repeat(bytes - envelope)) }
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    private fun AriToolProviderService.invokeAndRecord(
        argsJson: String = "",
        toolName: String = TOOL,
    ): RecordingCallback {
        val callback = RecordingCallback()
        // The binder itself, not Stub.asInterface(): asInterface needs
        // queryLocalInterface, which the unit-test android stubs do not run.
        (onBind(null) as IAriToolProvider).invoke("req-1", toolName, argsJson, callback)
        return callback
    }

    private fun AriToolProviderService.cancelRequest(requestId: String) =
        (onBind(null) as IAriToolProvider).cancel(requestId)

    private fun RecordingCallback.errorEnvelope(): JSONObject {
        val envelope = JSONObject(requireNotNull(resultJson))
        assertFalse(envelope.getBoolean("ok"))
        return envelope
    }

    private fun RecordingCallback.errorMessage(): String = errorEnvelope().getString("error")

    private fun RecordingCallback.errorCode(): String? =
        errorEnvelope().let { if (it.has("code")) it.getString("code") else null }

    @Test
    fun `an exception thrown by a handler becomes an error envelope instead of a crash`() {
        val callback = serviceThrowing(IllegalStateException("tool is unavailable"))
            .invokeAndRecord()

        assertEquals(1, callback.calls)
        assertEquals("req-1", callback.requestId)
        assertEquals("the app could not run this tool", callback.errorMessage())
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `an exception thrown by a handler keeps its message off the wire`() {
        val callback = serviceThrowing(IllegalStateException("row 7 of table secrets is null"))
            .invokeAndRecord()

        assertEquals("the app could not run this tool", callback.errorMessage())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `an error from a handler reports an envelope and still propagates`() {
        val error = NotImplementedError("An operation is not implemented")

        val callback = serviceThrowing(error).invokeAndRecord()

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals(listOf<Throwable>(error), uncaught)
    }

    @Test
    fun `a tool name the registry does not declare reports the unknown tool code`() {
        val service = RegistryService { tool(TOOL, DESCRIPTION) { handle { AriToolResult.ok() } } }

        val callback = service.invokeAndRecord(toolName = "take_note")

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_UNKNOWN_TOOL, callback.errorCode())
        assertEquals("the app declares no tool with this name", callback.errorMessage())
    }

    /** Ari opens a deeplink itself, so a call for one is a host mistake no retry fixes. */
    @Test
    fun `a deeplink tool runs no code when Ari invokes it anyway`() {
        val service = RegistryService {
            deeplink("open_order", "Opens the order.", "hpfield://order")
        }

        val callback = service.invokeAndRecord(toolName = "open_order")

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals(
            "this tool is a deeplink, so the app runs no code for it",
            callback.errorMessage(),
        )
    }

    @Test
    fun `an exception thrown by tools becomes an error envelope, not a crash`() {
        val callback = FailingRegistryService().invokeAndRecord()

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `an error the provider returns reaches Ari with its own text and no code`() {
        val callback = serviceReturning(AriToolResult.error("color is required"))
            .invokeAndRecord()

        assertEquals("color is required", callback.errorMessage())
        assertNull(callback.errorCode())
    }

    @Test
    fun `a result the provider cannot build becomes an error envelope instead of a crash`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) { handle { AriToolResult.ok { putNumber("x", Double.NaN) } } }
        }

        val callback = service.invokeAndRecord()

        assertEquals(1, callback.calls)
        assertEquals("the app could not run this tool", callback.errorMessage())
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `args reach the handler typed`() {
        var seen: ToolArgs? = null
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                string("color")
                int("count")
                bool("loud")
                handle { args ->
                    seen = args
                    AriToolResult.ok()
                }
            }
        }

        service.invokeAndRecord("""{"color":"red","count":2,"loud":true}""")

        val args = requireNotNull(seen)
        assertEquals("red", args.string("color"))
        assertEquals(2, args.int("count"))
        assertEquals(true, args.bool("loud"))
    }

    @Test
    fun `no args produces empty args rather than a failure`() {
        var seen: ToolArgs? = null
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                handle { args ->
                    seen = args
                    AriToolResult.ok()
                }
            }
        }

        val callback = service.invokeAndRecord()

        assertEquals(1, callback.calls)
        assertFalse(requireNotNull(seen).has("color"))
    }

    @Test
    fun `the request id reaches the handler`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) { handle { AriToolResult.ok { putString("request", requestId) } } }
        }

        val callback = service.invokeAndRecord()

        val data = JSONObject(requireNotNull(callback.resultJson)).getJSONObject("data")
        assertEquals("req-1", data.getString("request"))
    }

    /**
     * enforceCallingPermission() names the caller only on the binder thread. Called
     * after a dispatch it reads this process's own permissions, which would authorise
     * every caller. A dispatcher that queues instead of running inline shows whether
     * the gate runs before the coroutine starts.
     */
    @Test
    fun `the permission is enforced before the invocation is dispatched`() {
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        val service = PermissionRecordingService()

        service.invokeAndRecord()

        assertEquals(listOf("permission"), service.steps)

        scheduler.advanceUntilIdle()
        assertEquals(listOf("permission", "handler"), service.steps)
    }

    @Test
    fun `every binder call enforces Ari's permission`() {
        val service = PermissionRecordingService()

        service.callInvoke()
        service.callCancel()

        assertEquals(listOf("invoke", "cancel"), service.gated)
    }

    /**
     * The harness must enter the service where Ari enters it. A harness that reached the
     * handler another way would let a partner's test prove a path Ari never takes.
     */
    @Test
    fun `the test harness passes through the permission gate`() {
        val service = PermissionRecordingService()

        service.callHarness()

        assertEquals(listOf("harness"), service.gated)
    }

    @Test
    fun `the test harness reports the request id it names`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) { handle { AriToolResult.ok { putString("request", requestId) } } }
        }

        val result = service.invokeToolInTest(TOOL)

        assertEquals("test-request", (result as AriToolResult.Ok).payload.string("request"))
    }

    @Test
    fun `the test harness maps a throwing handler to the coded envelope, not to a throw`() {
        val service = serviceThrowing(IllegalStateException("row 7 of table secrets is null"))

        val result = service.invokeToolInTest(TOOL)

        val failure = result as AriToolResult.Failure
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
        assertEquals("the app could not run this tool", failure.message)
    }

    @Test
    fun `the test harness carries the pending intent of a launch result`() {
        val pendingIntent = mockk<PendingIntent>()
        val launch = AriToolResult.launch(pendingIntent, "Opening", immutable = true)

        val result = serviceReturning(launch).invokeToolInTest(TOOL)

        assertSame(pendingIntent, (result as AriToolResult.Launch).pendingIntent)
        assertEquals("Opening", result.spoken)
    }

    @Test
    fun `the test harness throws when the tool reports no result`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) { handle { awaitCancellation() } }
        }

        val thrown = assertThrows(IllegalStateException::class.java) {
            service.invokeToolInTest(TOOL)
        }

        assertTrue(requireNotNull(thrown.message).contains("reported no result"))
    }

    @Test
    fun `a result over the size cap never crosses binder`() {
        val oversized = "x".repeat(AriToolsContract.MAX_RESULT_BYTES)
        val service = serviceReturning(AriToolResult.ok { putString("blob", oversized) })

        val callback = service.invokeAndRecord()

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertFalse(requireNotNull(callback.resultJson).contains(oversized))
    }

    /**
     * The cap counts bytes, not characters. A euro sign is three UTF-8 bytes, so
     * a payload can sit under the cap in characters and far over it in bytes.
     */
    @Test
    fun `a result is capped by bytes, not by characters`() {
        val multiByte = "\u20ac".repeat(AriToolsContract.MAX_CLOUD_RESULT_BYTES / 2)
        val service = serviceReturning(AriToolResult.ok { putString("blob", multiByte) })

        val callback = service.invokeAndRecord()

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
    }

    @Test
    fun `a result at the cloud cap still reaches Ari`() {
        val service = serviceReturning(resultOfExactly(AriToolsContract.MAX_CLOUD_RESULT_BYTES))

        val callback = service.invokeAndRecord()

        assertTrue(JSONObject(requireNotNull(callback.resultJson)).getBoolean("ok"))
    }

    @Test
    fun `a result one byte over the cloud cap never crosses binder`() {
        val over = AriToolsContract.MAX_CLOUD_RESULT_BYTES + 1
        val service = serviceReturning(resultOfExactly(over))

        val callback = service.invokeAndRecord()

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        val message = callback.errorMessage()
        assertTrue(message, message.contains("$over bytes"))
        assertTrue(message, message.contains("${AriToolsContract.MAX_CLOUD_RESULT_BYTES} bytes"))
    }

    /** The cloud cap is the smaller one, so it is the number a partner reads. */
    @Test
    fun `a result over both caps names the cloud cap`() {
        val service = serviceReturning(resultOfExactly(AriToolsContract.MAX_RESULT_BYTES + 1))

        val message = service.invokeAndRecord().errorMessage()

        assertTrue(message, message.contains("${AriToolsContract.MAX_CLOUD_RESULT_BYTES} bytes"))
    }

    @Test
    fun `a result just under the size cap still reaches Ari`() {
        val service = serviceReturning(AriToolResult.ok { putString("blob", "x".repeat(1024)) })

        val callback = service.invokeAndRecord()

        assertTrue(JSONObject(requireNotNull(callback.resultJson)).getBoolean("ok"))
    }

    @Test
    fun `args over the size cap never reach the handler`() {
        var seen: ToolArgs? = null
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                handle { args ->
                    seen = args
                    AriToolResult.ok()
                }
            }
        }
        val oversized = "x".repeat(AriToolsContract.MAX_ARGS_BYTES)

        val callback = service.invokeAndRecord("""{"blob":"$oversized"}""")

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT, callback.errorCode())
        assertNull(seen)
    }

    @Test
    fun `a cancelled invocation reports the cancelled code`() {
        var stopped = false
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                handle {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        }
        val callback = service.invokeAndRecord()
        assertEquals(0, callback.calls)

        service.cancelRequest("req-1")

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_CANCELLED, callback.errorCode())
        assertTrue(stopped)
    }

    @Test
    fun `a cancel that arrives after the result reports nothing twice`() {
        val service = serviceReturning(AriToolResult.ok { putString("color", "red") })
        val callback = service.invokeAndRecord()
        assertEquals(1, callback.calls)

        service.cancelRequest("req-1")

        assertEquals(1, callback.calls)
    }

    @Test
    fun `a cancel for another request leaves this invocation running`() {
        var stopped = false
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                handle {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        }
        val callback = service.invokeAndRecord()

        service.cancelRequest("req-2")

        assertEquals(0, callback.calls)
        assertFalse(stopped)
    }

    @Test
    fun `a destroyed service still reports the invocation it cancelled`() {
        var stopped = false
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                handle {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                }
            }
        }
        val callback = service.invokeAndRecord()
        assertEquals(0, callback.calls)

        service.onDestroy()

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_CANCELLED, callback.errorCode())
        assertTrue(stopped)
    }

    @Test
    fun `an arg the tool cannot read is the models fault, not the apps`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                string("color", required = true)
                handle { args -> AriToolResult.ok { putString("color", args.string("color")) } }
            }
        }

        val callback = service.invokeAndRecord("""{"shade":"red"}""")

        assertEquals(1, callback.calls)
        assertEquals(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT, callback.errorCode())
        assertEquals("arg 'color' is missing", callback.errorMessage())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun `an arg of the wrong type is the models fault too`() {
        val service = RegistryService {
            tool(TOOL, DESCRIPTION) {
                int("count", required = true)
                handle { args -> AriToolResult.ok { putInt("count", args.int("count")) } }
            }
        }

        val callback = service.invokeAndRecord("""{"count":"many"}""")

        assertEquals(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT, callback.errorCode())
        assertEquals("arg 'count' is not an int", callback.errorMessage())
    }

    @Test
    fun `every other throwable from a handler stays the apps fault`() {
        val callback = serviceThrowing(IllegalArgumentException("bad row")).invokeAndRecord()

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, callback.errorCode())
        assertEquals("the app could not run this tool", callback.errorMessage())
    }

    @Test
    fun `malformed args json is the hosts fault, not the apps`() {
        val callback = serviceReturning(AriToolResult.ok()).invokeAndRecord("not json")

        assertEquals(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT, callback.errorCode())
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    /**
     * A PendingIntent is Parcelable, so it cannot travel inside the JSON envelope. It crosses
     * Binder next to it, and the envelope's kind is what tells Ari to expect one.
     */
    @Test
    fun `a launch result delivers its pending intent next to the envelope`() {
        val pendingIntent = mockk<PendingIntent>()
        val launch = AriToolResult.launch(pendingIntent, "Opening", immutable = true)

        val callback = serviceReturning(launch).invokeAndRecord()

        assertEquals(1, callback.calls)
        assertSame(pendingIntent, callback.launchIntent)
        val envelope = JSONObject(requireNotNull(callback.resultJson))
        assertEquals(
            AriToolsContract.RESULT_KIND_LAUNCH,
            envelope.getString(AriToolsContract.FIELD_RESULT_KIND),
        )
    }

    @Test
    fun `every other result kind delivers no pending intent`() {
        val ok = serviceReturning(AriToolResult.ok()).invokeAndRecord()
        val failed = serviceReturning(AriToolResult.error("no")).invokeAndRecord()

        assertNull(ok.launchIntent)
        assertNull(failed.launchIntent)
    }

    @Test
    fun `a service that declares no availability pushes nothing when it is created`() {
        val service = AvailabilityService(available = null)

        service.onCreate()

        assertEquals(emptyList<Set<String>>(), service.pushed)
    }

    /** A stale set on the Ari side must lose to the partner's own view once it runs. */
    @Test
    fun `a service that declares availability re-asserts it when it is created`() {
        val service = AvailabilityService(available = setOf(TOOL))

        service.onCreate()

        assertEquals(listOf(setOf(TOOL)), service.pushed)
    }

    @Test
    fun `a push that fails at service creation never ends the app`() {
        val service = AvailabilityService(
            available = setOf(TOOL),
            failWith = IllegalArgumentException("this app declares no tool named take_note"),
        )

        service.onCreate()

        assertEquals(emptyList<Throwable>(), uncaught)
    }

    private companion object {
        const val TOOL = "set_circle_color"
        const val DESCRIPTION = "Sets the colour of the circle shown in the app."
        const val BLOB = "blob"
    }
}
