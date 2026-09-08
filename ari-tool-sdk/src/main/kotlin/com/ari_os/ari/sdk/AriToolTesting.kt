package com.ari_os.ari.sdk

import android.app.PendingIntent
import android.os.IBinder

private const val TEST_REQUEST_ID = "test-request"

/**
 * Runs one tool call through the binder Ari calls, and returns the result Ari reads.
 *
 * The call enters the service where Ari enters it. So it passes the permission gate, it
 * names the caller, and it applies the size caps, the argument parsing and the error
 * envelope. A test therefore reports what a real invocation reports, over one code path.
 *
 * Your test module needs three things. Set `unitTests.isReturnDefaultValues = true`, put
 * `org.json:json` on the test classpath, and call `Dispatchers.setMain` first. A missing
 * `org.json` reports no result at all. A missing main dispatcher throws.
 *
 * Only a test can call this. Shipped code calls it outside a binder transaction, where
 * `enforceCallingPermission` always throws.
 *
 * @param argsJson Arguments, as Ari sends them. Empty text means no arguments.
 * @param requestId Id of this invocation, and the id [AriToolCall.requestId] reports.
 * @throws IllegalStateException when the tool reports no result.
 */
fun AriToolProviderService.invokeToolInTest(
    toolName: String,
    argsJson: String = "",
    requestId: String = TEST_REQUEST_ID,
): AriToolResult {
    val callback = RecordingCallback()
    // The binder itself, not Stub.asInterface(): asInterface needs queryLocalInterface,
    // which the android unit-test jar does not run.
    (onBind(null) as IAriToolProvider).invoke(requestId, toolName, argsJson, callback)
    val payload = checkNotNull(callback.resultJson) {
        "tool '$toolName' reported no result. A handler that still suspends does that, " +
            "and so does a stubbed org.json on the test classpath."
    }
    return AriToolResult.fromJson(payload, callback.launchIntent)
}

private class RecordingCallback : IAriToolCallback {

    var resultJson: String? = null
        private set

    var launchIntent: PendingIntent? = null
        private set

    override fun onResult(requestId: String, resultJson: String, launchIntent: PendingIntent?) {
        this.resultJson = resultJson
        this.launchIntent = launchIntent
    }

    override fun asBinder(): IBinder? = null
}
