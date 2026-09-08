package com.ari_os.ari.sdk

import org.json.JSONObject
import kotlin.math.truncate

/**
 * Thrown by a strict [ToolArgs] accessor when the argument is absent or holds another type.
 *
 * It is an [IllegalArgumentException], so a `catch` for that catches it too.
 */
class AriToolArgumentException internal constructor(message: String) :
    IllegalArgumentException(message)

/** Arguments of one tool invocation, read by name and type. */
class ToolArgs(private val values: JSONObject) {

    /** Whether [name] holds a value that is not JSON null. */
    fun has(name: String): Boolean = valueOf(name) != null

    /** Text of [name], or `null` when [name] is absent or holds an object or an array. */
    fun stringOrNull(name: String): String? = when (val value = valueOf(name)) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> null
    }

    /** Whole number of [name], or `null` when [name] is absent or is not a whole number. */
    fun intOrNull(name: String): Int? = numberOrNull(name)?.takeIf { it.isWholeInt() }?.toInt()

    /** Number of [name], or `null` when [name] is absent or is not a number. */
    fun numberOrNull(name: String): Double? = when (val value = valueOf(name)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    /** Flag of [name], or `null` when [name] is absent or is not a flag. */
    fun boolOrNull(name: String): Boolean? = when (val value = valueOf(name)) {
        is Boolean -> value
        is String -> value.lowercase().toBooleanStrictOrNull()
        else -> null
    }

    /** Text of [name]. Throws when [name] is absent or holds an object or an array. */
    fun string(name: String): String = required(name, "a string", stringOrNull(name))

    /** Whole number of [name]. Throws when [name] is absent or is not a whole number. */
    fun int(name: String): Int = required(name, "an int", intOrNull(name))

    /** Number of [name]. Throws when [name] is absent or is not a number. */
    fun number(name: String): Double = required(name, "a number", numberOrNull(name))

    /** Flag of [name]. Throws when [name] is absent or is not a flag. */
    fun bool(name: String): Boolean = required(name, "a bool", boolOrNull(name))

    override fun toString(): String = values.toString()

    private fun valueOf(name: String): Any? = values.opt(name)?.takeIf { it != JSONObject.NULL }

    private fun <T : Any> required(name: String, expected: String, value: T?): T =
        value ?: throw AriToolArgumentException(
            if (has(name)) "arg '$name' is not $expected" else "arg '$name' is missing"
        )

    // Truncating 3.7 to 3, or 1e20 to Int.MAX_VALUE, would hide the wrong type from the caller.
    private fun Double.isWholeInt(): Boolean =
        this == truncate(this) &&
            this >= Int.MIN_VALUE.toDouble() &&
            this <= Int.MAX_VALUE.toDouble()
}
