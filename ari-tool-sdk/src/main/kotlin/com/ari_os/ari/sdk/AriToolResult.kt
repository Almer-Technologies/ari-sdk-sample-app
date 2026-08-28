package com.ari_os.ari.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The result of one tool invocation.
 *
 * Serializes to the envelope Ari expects:
 * `{"ok":true,"data":{...}}` or `{"ok":false,"error":"..."}`.
 */
sealed interface AriToolResult {

    /** Render this result as the wire JSON envelope. */
    fun toJson(): String

    /** A successful invocation carrying an optional payload. */
    data class Ok(val data: JsonObject) : AriToolResult {
        override fun toJson(): String = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("ok", true)
                put("data", data)
            }
        )
    }

    /**
     * A failed invocation. [message] is shown to the user by Ari, so write it
     * as a short explanation rather than a stack trace.
     */
    data class Error(val message: String) : AriToolResult {
        override fun toJson(): String = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("ok", false)
                put("error", message)
            }
        )
    }

    companion object {

        /**
         * Success with string/number/boolean pairs as the payload.
         *
         * Only [String], [Number], and [Boolean] values are encoded as JSON
         * primitives; any other value type is silently `toString()`ed into a
         * JSON string instead of being encoded as nested JSON. Use
         * [ok] with a [JsonObject] for structured (list/object) payloads.
         */
        fun ok(vararg pairs: Pair<String, Any?>): AriToolResult = Ok(
            buildJsonObject {
                pairs.forEach { (key, value) ->
                    put(
                        key,
                        when (value) {
                            null -> JsonPrimitive(null as String?)
                            is String -> JsonPrimitive(value)
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            else -> JsonPrimitive(value.toString())
                        }
                    )
                }
            }
        )

        /** Success with a prebuilt payload. */
        fun ok(data: JsonObject): AriToolResult = Ok(data)

        /** Failure with a user-facing explanation. */
        fun error(message: String): AriToolResult = Error(message)
    }
}
