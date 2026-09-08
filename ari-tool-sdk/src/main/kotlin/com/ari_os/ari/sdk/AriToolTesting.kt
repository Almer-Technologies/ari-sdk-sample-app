package com.ari_os.ari.sdk

import android.app.PendingIntent
import android.os.IBinder

private const val TEST_REQUEST_ID = "test-request"

/**
 * Runs one tool call through the binder Ari calls, and returns the result Ari reads.
 *
 * Only a test can call this. Shipped code calls it outside a binder transaction, where
 * `enforceCallingPermission` always throws.
 *
 * The test module needs `unitTests.isReturnDefaultValues = true`, `org.json:json` on the
 * test classpath, and a main dispatcher. See "Testing a handler" in the module README.
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
