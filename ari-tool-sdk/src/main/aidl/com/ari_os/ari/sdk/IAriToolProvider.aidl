package com.ari_os.ari.sdk;

import com.ari_os.ari.sdk.IAriToolCallback;

/** A provider app's tool surface. Implement via AriToolProviderService. */
interface IAriToolProvider {

    /** AIDL wire protocol version this provider speaks. */
    int getApiVersion();

    /**
     * Optional runtime declaration. Return null to use the manifest resource.
     * When non-null: a JSON array of tool declarations.
     */
    @nullable String listTools();

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
}
