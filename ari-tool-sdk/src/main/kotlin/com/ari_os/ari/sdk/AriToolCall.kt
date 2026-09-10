package com.ari_os.ari.sdk

/**
 * One tool invocation.
 *
 * @property requestId Id of this invocation, and the id Ari cancels it by.
 */
data class AriToolCall(
    val requestId: String,
)
