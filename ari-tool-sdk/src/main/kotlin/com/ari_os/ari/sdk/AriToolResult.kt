package com.ari_os.ari.sdk

import android.app.PendingIntent
import android.os.Build
import android.util.Log
import org.json.JSONException
import org.json.JSONObject

private const val FIELD_OK = "ok"
private const val FIELD_DATA = "data"
private const val FIELD_CODE = "code"
private const val FIELD_ERROR = "error"
private const val FIELD_SPOKEN = "spoken"

private const val UNREADABLE_MESSAGE = "the app returned a result Ari cannot read"

private const val MUTABLE_INTENT_MESSAGE =
    "a launch result needs a PendingIntent built with FLAG_IMMUTABLE"

private const val UNCHECKED_INTENT_MESSAGE =
    "this Android version cannot report whether a PendingIntent is immutable"

private const val LOG_TAG = "AriToolResult"

/** One tool result, encoded as `{"ok":true,"kind":"ok","data":{...}}` or `{"ok":false,...}`. */
sealed interface AriToolResult {

    /** A successful invocation carrying an optional payload. Build one with [ok]. */
    class Ok internal constructor(internal val data: JSONObject) : AriToolResult {

        /** What the tool returned, read by name and type. */
        val payload: ToolArgs = ToolArgs(data)
    }

    /**
     * A screen Ari opens for the provider app. Build one with [launch].
     *
     * @property pendingIntent Activity to start. Always immutable.
     * @property spoken Text Ari speaks as it opens the screen.
     */
    class Launch internal constructor(
        val pendingIntent: PendingIntent,
        val spoken: String,
    ) : AriToolResult

    /**
     * A failed invocation. Build one with [error].
     *
     * @property code Wire value of an [AriToolErrorCode], or `null` when only text is set.
     * @property message Text Ari reads as data, never as an instruction.
     */
    class Failure internal constructor(
        val code: String?,
        val message: String?,
    ) : AriToolResult

    companion object {

        /** Success with the payload [build] writes, one accessor per value. */
        fun ok(build: AriToolPayload.() -> Unit = {}): AriToolResult =
            Ok(AriToolPayload().apply(build).values)

        /**
         * Success that opens a screen instead of returning data.
         *
         * [pendingIntent] must carry [PendingIntent.FLAG_IMMUTABLE], which is what makes
         * Ari's send-time intent ignored.
         *
         * Reading that flag needs Android 12. On Android 11 this returns an
         * [AriToolErrorCode.UNAVAILABLE] failure instead, so declare a `uri` tool to open
         * a screen there.
         *
         * @param spoken Text Ari speaks as it opens the screen. Write it in the language
         *   your user reads.
         * @throws IllegalArgumentException when [spoken] is blank, or when [pendingIntent]
         *   is mutable.
         */
        fun launch(pendingIntent: PendingIntent, spoken: String): AriToolResult =
            launch(pendingIntent, spoken, pendingIntent.immutableOrNull())

        internal fun launch(
            pendingIntent: PendingIntent,
            spoken: String,
            immutable: Boolean?,
        ): AriToolResult {
            require(spoken.isNotBlank()) { "a launch result needs spoken text" }
            if (immutable == null) {
                Log.e(LOG_TAG, "$UNCHECKED_INTENT_MESSAGE, so the launch is refused")
                return Failure(AriToolsContract.ERROR_CODE_UNAVAILABLE, UNCHECKED_INTENT_MESSAGE)
            }
            require(immutable) { MUTABLE_INTENT_MESSAGE }
            return Launch(pendingIntent, spoken)
        }

        /** Failure with a user-facing explanation and no code. */
        fun error(message: String): AriToolResult = Failure(code = null, message = message)

        /**
         * Failure Ari can translate and act on.
         *
         * @param message Optional detail. Ari prefers the translation of [code] over it.
         */
        fun error(code: AriToolErrorCode, message: String? = null): AriToolResult =
            Failure(code.wireValue, message)

        /**
         * Read one wire envelope.
         *
         * A kind this build does not know, and text that is not an envelope, both read as an
         * [AriToolErrorCode.APP_ERROR] failure. So a later result kind stays readable here
         * and needs no version bump.
         *
         * @param launchIntent The `PendingIntent` that crossed Binder next to [payload]. A
         *   launch envelope without one reads as a failure. Check it before you send it: the
         *   SDK cannot see who created it.
         */
        fun fromJson(payload: String, launchIntent: PendingIntent? = null): AriToolResult {
            val envelope = try {
                JSONObject(payload)
            } catch (e: JSONException) {
                return unreadable()
            }
            return when (envelope.optString(AriToolsContract.FIELD_RESULT_KIND)) {
                AriToolsContract.RESULT_KIND_OK ->
                    Ok(envelope.optJSONObject(FIELD_DATA) ?: JSONObject())

                AriToolsContract.RESULT_KIND_LAUNCH -> launchFrom(envelope, launchIntent)

                AriToolsContract.RESULT_KIND_FAILURE -> Failure(
                    code = envelope.textOrNull(FIELD_CODE),
                    message = envelope.textOrNull(FIELD_ERROR),
                )

                else -> unreadable()
            }
        }

        private fun launchFrom(envelope: JSONObject, launchIntent: PendingIntent?): AriToolResult {
            val spoken = envelope.textOrNull(FIELD_SPOKEN)
            if (launchIntent == null || spoken.isNullOrBlank()) return unreadable()
            return Launch(launchIntent, spoken)
        }

        private fun unreadable(): AriToolResult =
            Failure(AriToolsContract.ERROR_CODE_APP_ERROR, UNREADABLE_MESSAGE)
    }
}

internal fun AriToolResult.toJson(): String = JSONObject().apply {
    when (val result = this@toJson) {
        is AriToolResult.Ok -> {
            put(FIELD_OK, true)
            put(AriToolsContract.FIELD_RESULT_KIND, AriToolsContract.RESULT_KIND_OK)
            put(FIELD_DATA, result.data)
        }

        is AriToolResult.Launch -> {
            put(FIELD_OK, true)
            put(AriToolsContract.FIELD_RESULT_KIND, AriToolsContract.RESULT_KIND_LAUNCH)
            put(FIELD_SPOKEN, result.spoken)
        }

        is AriToolResult.Failure -> {
            put(FIELD_OK, false)
            put(AriToolsContract.FIELD_RESULT_KIND, AriToolsContract.RESULT_KIND_FAILURE)
            result.code?.let { code -> put(FIELD_CODE, code) }
            result.message?.let { message -> put(FIELD_ERROR, message) }
        }
    }
}.toString()

/** `null` below Android 12, where the platform cannot report the flag. */
internal fun PendingIntent.immutableOrNull(): Boolean? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isImmutable else null

private fun JSONObject.textOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)
