package com.ari_os.ari.sdk

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.truncate

/**
 * Thrown by a strict [ToolArgs] accessor when the argument is absent or holds another type.
 *
 * It is an [IllegalArgumentException], so a `catch` for that catches it too.
 */
class AriToolArgumentException internal constructor(message: String) :
    IllegalArgumentException(message)

/**
 * One JSON object of a tool invocation, read by name and type.
 *
 * It holds the arguments Ari sends, or the payload a tool returns.
 */
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
    fun numberOrNull(name: String): Double? = valueOf(name)?.asDouble()

    /** Flag of [name], or `null` when [name] is absent or is not a flag. */
    fun boolOrNull(name: String): Boolean? = when (val value = valueOf(name)) {
        is Boolean -> value
        is String -> value.lowercase().toBooleanStrictOrNull()
        else -> null
    }

    /**
     * Whole numbers of [name], or `null` when [name] is absent, is not a list, holds an
     * element that is not a whole number, or holds over
     * [AriToolsContract.MAX_LIST_ELEMENTS] elements.
     */
    fun intListOrNull(name: String): List<Int>? =
        elementsOf(name) { element -> element.asDouble()?.takeIf { it.isWholeInt() }?.toInt() }

    /**
     * Text values of [name], or `null` when [name] is absent, is not a list, holds an
     * element that is an object or an array, or holds over
     * [AriToolsContract.MAX_LIST_ELEMENTS] elements.
     */
    fun stringListOrNull(name: String): List<String>? = elementsOf(name) { element ->
        when (element) {
            is String -> element
            is Number, is Boolean -> element.toString()
            else -> null
        }
    }

    /** Text of [name]. Throws when [name] is absent or holds an object or an array. */
    fun string(name: String): String = required(name, "a string", stringOrNull(name))

    /** Whole number of [name]. Throws when [name] is absent or is not a whole number. */
    fun int(name: String): Int = required(name, "an int", intOrNull(name))

    /** Number of [name]. Throws when [name] is absent or is not a number. */
    fun number(name: String): Double = required(name, "a number", numberOrNull(name))

    /** Flag of [name]. Throws when [name] is absent or is not a flag. */
    fun bool(name: String): Boolean = required(name, "a bool", boolOrNull(name))

    /** Whole numbers of [name]. Throws when [name] is absent or is not a list of them. */
    fun intList(name: String): List<Int> = required(name, "a list of ints", intListOrNull(name))

    /** Text values of [name]. Throws when [name] is absent or is not a list of them. */
    fun stringList(name: String): List<String> =
        required(name, "a list of strings", stringListOrNull(name))

    override fun toString(): String = values.toString()

    private fun valueOf(name: String): Any? = values.opt(name)?.takeIf { it != JSONObject.NULL }

    private fun <T : Any> required(name: String, expected: String, value: T?): T =
        value ?: throw AriToolArgumentException(
            if (has(name)) "arg '$name' is not $expected" else "arg '$name' is missing"
        )

    // One unreadable element voids the list. A shorter list would let the tool act on a
    // set the model never sent, under the one confirmation the user gave for the whole
    // set. A single value is not a one-element list: that is a difference in how many
    // things the call names, not in how one value is written.
    private fun <T : Any> elementsOf(name: String, read: (Any) -> T?): List<T>? {
        val array = valueOf(name) as? JSONArray ?: return null
        if (array.length() > AriToolsContract.MAX_LIST_ELEMENTS) return null
        return (0 until array.length()).map { index ->
            val element = array.opt(index)?.takeIf { it != JSONObject.NULL } ?: return null
            read(element) ?: return null
        }
    }

    private fun Any.asDouble(): Double? = when (this) {
        is Number -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }

    // Truncating 3.7 to 3, or 1e20 to Int.MAX_VALUE, would hide the wrong type from the caller.
    private fun Double.isWholeInt(): Boolean =
        this == truncate(this) &&
            this >= Int.MIN_VALUE.toDouble() &&
            this <= Int.MAX_VALUE.toDouble()
}
