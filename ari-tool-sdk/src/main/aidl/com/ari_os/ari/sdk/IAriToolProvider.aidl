package com.ari_os.ari.sdk;

import com.ari_os.ari.sdk.IAriToolCallback;

/** A provider app's tool surface. Implement via AriToolProviderService. */
interface IAriToolProvider {

    /**
     * Invoke one tool. Returns immediately; the result arrives on callback.
     *
     * @param requestId Correlation id to echo back.
     * @param toolName  Declared tool name.
     * @param argsJson  JSON object matching the tool's declared args.
     * @param callback  Where to deliver the result.
     */
    oneway void invoke(String requestId, String toolName, String argsJson,
                       IAriToolCallback callback);

    /**
     * Stop an invocation. The provider cancels the coroutine running the tool
     * and reports the "cancelled" error code on the callback.
     *
     * Keep this last: a new method takes a new transaction code, so an older
     * provider stays callable.
     *
     * @param requestId Correlation id from the matching invoke() call. An id
     *   that already finished, or was never seen, is ignored.
     */
    oneway void cancel(String requestId);
}
