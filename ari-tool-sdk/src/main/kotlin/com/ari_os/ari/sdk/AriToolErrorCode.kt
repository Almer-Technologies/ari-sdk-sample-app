package com.ari_os.ari.sdk

/**
 * A failure reason a provider app can report. Ari maps the code to its own
 * translated text, and uses it to decide whether a retry can help.
 */
enum class AriToolErrorCode(val wireValue: String) {

    /** The arguments do not fit the tool, so Ari can ask the model to correct them. */
    INVALID_ARGUMENT(AriToolsContract.ERROR_CODE_INVALID_ARGUMENT),

    /** The app declares no tool with this name. */
    UNKNOWN_TOOL(AriToolsContract.ERROR_CODE_UNKNOWN_TOOL),

    /** The app cannot run the tool right now, so a later try may work. */
    UNAVAILABLE(AriToolsContract.ERROR_CODE_UNAVAILABLE),

    /** The user or a policy refused the tool, so no retry works. */
    DENIED(AriToolsContract.ERROR_CODE_DENIED),

    /** The app failed while it ran the tool. */
    APP_ERROR(AriToolsContract.ERROR_CODE_APP_ERROR),
}
