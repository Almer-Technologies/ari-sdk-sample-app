package com.ari_os.ari.sdk

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Base service a provider app extends to expose tools to Ari. */
abstract class AriToolProviderService : Service() {

    // The scope names no dispatcher: each invocation names the main dispatcher at its
    // launch site instead. So building the service resolves no main looper, and a unit
    // test can read tools() off a service it constructed itself.
    private val scope = CoroutineScope(SupervisorJob())

    private val pending = ConcurrentHashMap<String, PendingInvocation>()

    /**
     * Every tool this app exposes, declared with [ariTools].
     *
     * One registry serves both jobs: Ari reads the declarations from it, and the SDK
     * dispatches each call to the handler declared next to them. So a tool and the
     * code that runs it cannot drift apart.
     *
     * The SDK calls this on a binder thread for each tool call, and on the main thread
     * when the service is created. Keep it cheap, and read only state that is safe on
     * both. Hold the registry in a field when building it costs anything.
     *
     * Each handler runs in its own coroutine on the main dispatcher. So two calls to one
     * tool interleave at every suspension point. Guard any state they share.
     *
     * Ari can cancel a handler, and so can the service dying. Let [CancellationException]
     * leave your code: never catch it, and never wrap your work in a `catch (e: Exception)`
     * that swallows it. The SDK then reports the `cancelled` code for you.
     */
    abstract fun tools(): AriToolRegistry

    /**
     * Names of the declared tools this app can run right now, or null when every
     * declared tool is always available.
     *
     * Override it only when availability changes at runtime. The SDK pushes the answer
     * once, at service creation, so a stale view on the Ari side is corrected as soon
     * as this process runs. Ari keeps a pushed set until the next push, so call
     * [AriTools.setAvailable] yourself whenever the answer changes after that.
     *
     * The SDK calls this on the main thread. Keep it cheap, and never block.
     */
    open fun availableTools(): Set<String>? = null

    private val binder = object : IAriToolProvider.Stub() {

        override fun invoke(
            requestId: String,
            toolName: String,
            argsJson: String,
            callback: IAriToolCallback,
        ) {
            enforceAriPermission()
            if (argsJson.utf8Size() > AriToolsContract.MAX_ARGS_BYTES) {
                Log.w(TAG, "args over the size cap for $toolName ($requestId)")
                send(callback, requestId, Delivery(ARGS_TOO_LARGE.toJson()))
                return
            }
            // Read the caller before any dispatch. Binder.getCallingUid() names
            // the caller only on the binder thread, and reports this process's
            // own uid everywhere else.
            val call = AriToolCall(callingPackage(), requestId)
            when (val found = findTool(toolName)) {
                is Lookup.Refused -> send(callback, requestId, Delivery(found.result.toJson()))
                is Lookup.Found -> start(call, toolName, found.handler, argsJson, callback)
            }
        }

        override fun cancel(requestId: String) {
            enforceAriPermission()
            val invocation = pending[requestId]
            if (invocation == null) {
                Log.d(TAG, "cancel for unknown request $requestId")
                return
            }
            Log.d(TAG, "cancelling request $requestId")
            invocation.job.cancel()
        }
    }

    /**
     * Ari's bind permission, enforced per transaction. `onBind` cannot enforce it,
     * because it runs outside the caller's binder transaction, where
     * `enforceCallingPermission` always throws.
     */
    internal open fun enforceAriPermission() = enforceCallingPermission(
        AriToolsContract.PERMISSION_BIND_TOOL_PROVIDER,
        "an Ari tool call needs ${AriToolsContract.PERMISSION_BIND_TOOL_PROVIDER}",
    )

    /** Caller of the current binder transaction. Only correct on the binder thread. */
    internal open fun callingPackage(): String {
        val manager = packageManager ?: return ""
        val uid = Binder.getCallingUid()
        return manager.getPackagesForUid(uid)?.singleOrNull()
            ?: manager.getNameForUid(uid)
            ?: ""
    }

    // The one place a tool name turns into code to run, so every name Ari can
    // send has exactly one answer.
    private fun findTool(toolName: String): Lookup {
        val tool = try {
            tools().find(toolName)
        } catch (e: Exception) {
            Log.w(TAG, "tools() failed while it looked up $toolName", e)
            return Lookup.Refused(APP_FAILED)
        }
        if (tool == null) {
            Log.w(TAG, "no declared tool named $toolName")
            return Lookup.Refused(UNKNOWN_TOOL)
        }
        val handler = tool.handler
        if (handler == null) {
            Log.w(TAG, "tool $toolName is a deeplink, so Ari opens it instead of invoking it")
            return Lookup.Refused(NOT_INVOCABLE)
        }
        return Lookup.Found(handler)
    }

    private fun start(
        call: AriToolCall,
        toolName: String,
        handler: AriToolHandler,
        argsJson: String,
        callback: IAriToolCallback,
    ) {
        // Lazy: a cancel can arrive, and the body can finish, only once the
        // pending entry exists.
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            deliver(call.requestId, runTool(call, toolName, handler, argsJson))
        }
        pending[call.requestId] = PendingInvocation(callback, job)
        // Report from here, not from the body: the body never reaches its end
        // when the job is cancelled. A oneway call still leaves a dying process,
        // so Ari hears about an invocation the service died on.
        job.invokeOnCompletion { cause ->
            cause?.let { deliver(call.requestId, Delivery(failureFor(it).toJson())) }
        }
        job.start()
    }

    private fun failureFor(cause: Throwable): AriToolResult =
        if (cause is CancellationException) CANCELLED else APP_FAILED

    private fun deliver(requestId: String, delivery: Delivery) {
        val invocation = pending.remove(requestId) ?: return
        send(invocation.callback, requestId, delivery)
    }

    private fun send(callback: IAriToolCallback, requestId: String, delivery: Delivery) {
        try {
            callback.onResult(requestId, delivery.payload, delivery.launchIntent)
        } catch (e: RemoteException) {
            Log.w(TAG, "callback delivery failed for $requestId", e)
        }
    }

    private suspend fun runTool(
        call: AriToolCall,
        toolName: String,
        handler: AriToolHandler,
        argsJson: String,
    ): Delivery {
        val args = try {
            if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
        } catch (e: JSONException) {
            Log.w(TAG, "unreadable args for $toolName (${call.requestId})", e)
            return Delivery(INVALID_ARGS.toJson())
        }
        val result = try {
            handler(call, ToolArgs(args))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AriToolArgumentException) {
            Log.w(TAG, "unusable args for $toolName (${call.requestId})", e)
            AriToolResult.Failure(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "$toolName failed (${call.requestId})", e)
            APP_FAILED
        }
        val payload = try {
            result.toJson()
        } catch (e: Exception) {
            Log.w(TAG, "result not serializable for $toolName (${call.requestId})", e)
            return Delivery(UNREADABLE_RESULT.toJson())
        }
        if (payload.utf8Size() > AriToolsContract.MAX_RESULT_BYTES) {
            Log.w(TAG, "result over the size cap for $toolName (${call.requestId})")
            return Delivery(RESULT_TOO_LARGE.toJson())
        }
        return Delivery(payload, (result as? AriToolResult.Launch)?.pendingIntent)
    }

    // Binder limits bytes, and a UTF-8 character can be four of them.
    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.Main.immediate) {
            try {
                val available = availableTools() ?: return@launch
                Log.d(TAG, "re-asserting ${available.size} available tools")
                sendAvailability(available)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A push is never worth ending the partner's process over.
                Log.e(TAG, "the availability push at service creation failed", e)
            }
        }
    }

    internal open suspend fun sendAvailability(names: Set<String>): AriAvailabilityResult =
        AriTools.setAvailable(this, names, tools())

    override fun onBind(intent: Intent?): IBinder {
        warnWhenBindPermissionMissing()
        return binder
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed, cancelling pending invocations")
        scope.cancel()
        super.onDestroy()
    }

    private fun warnWhenBindPermissionMissing() {
        val manager = packageManager ?: return
        val required = try {
            manager.getServiceInfo(ComponentName(this, javaClass), 0).permission
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "cannot read own <service> entry", e)
            return
        }
        if (required != AriToolsContract.PERMISSION_BIND_TOOL_PROVIDER) {
            Log.e(
                TAG,
                "<service> requires '$required', so any app can bind it. " +
                    "Set android:permission to ${AriToolsContract.PERMISSION_BIND_TOOL_PROVIDER}.",
            )
        }
    }

    private sealed interface Lookup {
        class Found(val handler: AriToolHandler) : Lookup
        class Refused(val result: AriToolResult) : Lookup
    }

    private class PendingInvocation(val callback: IAriToolCallback, val job: Job)

    private class Delivery(val payload: String, val launchIntent: PendingIntent? = null)

    companion object {
        // android.util.Log, not the repo-standard logging(): partner apps
        // compile against this module, so it must stay free of internal
        // leviathan modules and of third-party logging dependencies.
        private const val TAG = "AriToolProvider"

        // Ari sends error text to the cloud and speaks it to the user, so a
        // throwable's own message must never reach the wire.
        private val APP_FAILED = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_APP_ERROR,
            "the app could not run this tool",
        )
        private val UNREADABLE_RESULT = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_APP_ERROR,
            "the app returned a result Ari cannot read",
        )
        private val INVALID_ARGS = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_INVALID_ARGUMENT,
            "the app cannot read these arguments",
        )
        private val CANCELLED = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_CANCELLED,
            "the invocation stopped before it finished",
        )
        private val ARGS_TOO_LARGE = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_INVALID_ARGUMENT,
            "the arguments are too large for this app",
        )
        private val RESULT_TOO_LARGE = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_APP_ERROR,
            "the app returned a result that is too large",
        )
        private val UNKNOWN_TOOL = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_UNKNOWN_TOOL,
            "the app declares no tool with this name",
        )
        private val NOT_INVOCABLE = AriToolResult.Failure(
            AriToolsContract.ERROR_CODE_APP_ERROR,
            "this tool is a deeplink, so the app runs no code for it",
        )
    }
}
