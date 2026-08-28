package com.ari_os.ari.sdk;

/** Ari's sink for one tool invocation's result. */
oneway interface IAriToolCallback {
    /**
     * @param requestId Correlation id from the matching invoke() call.
     * @param resultJson {"ok":true,"data":{...}} or {"ok":false,"error":"..."}
     */
    void onResult(String requestId, String resultJson);
}
