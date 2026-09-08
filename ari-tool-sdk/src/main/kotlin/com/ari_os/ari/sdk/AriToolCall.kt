package com.ari_os.ari.sdk

/**
 * Who asked for one tool invocation, and under which id.
 *
 * @property callerPackage App that called, taken from the binder transaction. The
 *   SDK only reports it, so check it yourself when a tool needs to. Empty when the
 *   platform names no single package for the caller.
 * @property requestId Id of this invocation, and the id Ari cancels it by.
 */
data class AriToolCall(
    val callerPackage: String,
    val requestId: String,
)
