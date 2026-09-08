package com.ari_os.ari.sdk

import org.json.JSONArray
import org.json.JSONObject

/** Builder of a result payload. One accessor per JSON type the payload may hold. */
class AriToolPayload internal constructor() {

    internal val values = JSONObject()

    /** Writes text under [name], or JSON null when [value] is null. */
    fun putString(name: String, value: String?) {
        put(name, value)
    }

    /** Writes a whole number under [name], or JSON null when [value] is null. */
    fun putInt(name: String, value: Int?) {
        put(name, value)
    }

    /** Writes a number under [name]. Rejects NaN and the infinities, which JSON cannot hold. */
    fun putNumber(name: String, value: Double?) {
        put(name, value)
    }

    /** Writes true or false under [name], or JSON null when [value] is null. */
    fun putBool(name: String, value: Boolean?) {
        put(name, value)
    }

    /** Writes the object [build] fills under [name]. */
    fun putObject(name: String, build: AriToolPayload.() -> Unit) {
        put(name, AriToolPayload().apply(build).values)
    }

    /** Writes the list [build] fills under [name]. */
    fun putList(name: String, build: AriToolPayloadList.() -> Unit) {
        put(name, AriToolPayloadList().apply(build).values)
    }

    private fun put(name: String, value: Any?) {
        values.put(name, value ?: JSONObject.NULL)
    }
}

/** Builder of a list inside a result payload. One accessor per JSON type the list may hold. */
class AriToolPayloadList internal constructor() {

    internal val values = JSONArray()

    /** Adds text to the list. */
    fun addString(value: String) {
        values.put(value)
    }

    /** Adds a whole number to the list. */
    fun addInt(value: Int) {
        values.put(value)
    }

    /** Adds a number to the list. Rejects NaN and the infinities, which JSON cannot hold. */
    fun addNumber(value: Double) {
        values.put(value)
    }

    /** Adds true or false to the list. */
    fun addBool(value: Boolean) {
        values.put(value)
    }

    /** Adds the object [build] fills to the list. */
    fun addObject(build: AriToolPayload.() -> Unit) {
        values.put(AriToolPayload().apply(build).values)
    }

    /** Adds the list [build] fills to the list. */
    fun addList(build: AriToolPayloadList.() -> Unit) {
        values.put(AriToolPayloadList().apply(build).values)
    }
}
