package com.ari_os.ari.sdk

import android.app.PendingIntent
import io.mockk.mockk
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class AriToolResultTest {

    private val pendingIntent = mockk<PendingIntent>()

    private fun envelope(result: AriToolResult) = JSONObject(result.toJson())

    private fun payload(result: AriToolResult) = envelope(result).getJSONObject("data")

    @Test
    fun `ok result carries data under the wire envelope`() {
        val envelope = envelope(AriToolResult.ok { putString("color", "blue") })
        assertTrue(envelope.getBoolean("ok"))
        assertEquals("blue", envelope.getJSONObject("data").getString("color"))
    }

    @Test
    fun `error result carries the message and ok false`() {
        val envelope = envelope(AriToolResult.error("unknown colour: cerulean"))
        assertFalse(envelope.getBoolean("ok"))
        assertEquals("unknown colour: cerulean", envelope.getString("error"))
    }

    @Test
    fun `a coded error carries only its code when the provider adds no text`() {
        val envelope = envelope(AriToolResult.error(AriToolErrorCode.UNAVAILABLE))

        assertFalse(envelope.getBoolean("ok"))
        assertEquals("unavailable", envelope.getString("code"))
        assertFalse(envelope.has("error"))
    }

    @Test
    fun `a coded error keeps the provider text next to its code`() {
        val envelope = envelope(AriToolResult.error(AriToolErrorCode.DENIED, "the user said no"))

        assertEquals("denied", envelope.getString("code"))
        assertEquals("the user said no", envelope.getString("error"))
    }

    @Test
    fun `every code a provider can set is one the contract publishes`() {
        assertEquals(
            listOf("invalid_argument", "unknown_tool", "unavailable", "denied", "app_error"),
            AriToolErrorCode.entries.map { code -> code.wireValue },
        )
    }

    @Test
    fun `no provider code reports a cancellation, because only the sdk sees one`() {
        assertFalse(
            AriToolsContract.ERROR_CODE_CANCELLED in
                AriToolErrorCode.entries.map { code -> code.wireValue }
        )
    }

    @Test
    fun `a provider error carries no code, because its text cannot be translated`() {
        val envelope = envelope(AriToolResult.error("unknown colour: cerulean"))

        assertFalse(envelope.has("code"))
        assertEquals("unknown colour: cerulean", envelope.getString("error"))
    }

    @Test
    fun `ok with no data still produces a valid envelope`() {
        assertTrue(envelope(AriToolResult.ok()).getBoolean("ok"))
    }

    @Test
    fun `each accessor keeps its value typed rather than stringified`() {
        val data = payload(
            AriToolResult.ok {
                putString("text", "blue")
                putInt("count", 3)
                putNumber("ratio", 1.5)
                putBool("enabled", true)
            }
        )

        assertEquals("blue", data.getString("text"))
        assertEquals(3, data.getInt("count"))
        assertEquals(1.5, data.getDouble("ratio"), 0.0)
        assertTrue(data.getBoolean("enabled"))

        assertFalse(data.get("count") is String)
        assertFalse(data.get("ratio") is String)
        assertFalse(data.get("enabled") is String)
    }

    @Test
    fun `a null value writes JSON null`() {
        val data = payload(
            AriToolResult.ok {
                putString("text", null)
                putInt("count", null)
                putNumber("ratio", null)
                putBool("enabled", null)
            }
        )

        assertTrue(data.isNull("text"))
        assertTrue(data.isNull("count"))
        assertTrue(data.isNull("ratio"))
        assertTrue(data.isNull("enabled"))
    }

    @Test
    fun `a nested object and a nested list keep their JSON shape`() {
        val data = payload(
            AriToolResult.ok {
                putObject("size") {
                    putInt("width", 10)
                    putObject("inner") { putString("deep", "yes") }
                }
                putList("items") {
                    addString("a")
                    addInt(1)
                    addNumber(2.5)
                    addBool(false)
                    addObject { putString("name", "row") }
                    addList { addInt(7) }
                }
            }
        )

        assertEquals(10, data.getJSONObject("size").getInt("width"))
        assertEquals("yes", data.getJSONObject("size").getJSONObject("inner").getString("deep"))

        val items = data.getJSONArray("items")
        assertEquals("a", items.getString(0))
        assertEquals(1, items.getInt(1))
        assertEquals(2.5, items.getDouble(2), 0.0)
        assertFalse(items.getBoolean(3))
        assertEquals("row", items.getJSONObject(4).getString("name"))
        assertEquals(7, items.getJSONArray(5).getInt(0))
    }

    @Test
    fun `an empty nested object and list still encode`() {
        val data = payload(
            AriToolResult.ok {
                putObject("size") {}
                putList("items") {}
            }
        )

        assertEquals(0, data.getJSONObject("size").length())
        assertEquals(0, data.getJSONArray("items").length())
    }

    @Test
    fun `every envelope names its kind`() {
        assertEquals(
            AriToolsContract.RESULT_KIND_OK,
            envelope(AriToolResult.ok()).getString(AriToolsContract.FIELD_RESULT_KIND),
        )
        assertEquals(
            AriToolsContract.RESULT_KIND_FAILURE,
            envelope(AriToolResult.error("no")).getString(AriToolsContract.FIELD_RESULT_KIND),
        )
    }

    @Test
    fun `a known kind round trips through the envelope`() {
        val ok = AriToolResult.fromJson(AriToolResult.ok { putString("color", "red") }.toJson())
        assertEquals("red", (ok as AriToolResult.Ok).data.getString("color"))

        val coded = AriToolResult.error(AriToolErrorCode.DENIED, "the user said no").toJson()
        val failure = AriToolResult.fromJson(coded) as AriToolResult.Failure
        assertEquals("denied", failure.code)
        assertEquals("the user said no", failure.message)
    }

    @Test
    fun `an ok envelope with no data reads as an empty payload`() {
        val ok = AriToolResult.fromJson(AriToolResult.ok().toJson())

        assertEquals(0, (ok as AriToolResult.Ok).data.length())
    }

    @Test
    fun `the payload of an ok result reads what the builder wrote`() {
        val ok = AriToolResult.ok {
            putString("color", "blue")
            putInt("count", 3)
            putBool("loud", true)
        }

        val payload = (ok as AriToolResult.Ok).payload
        assertEquals("blue", payload.string("color"))
        assertEquals(3, payload.int("count"))
        assertEquals(true, payload.bool("loud"))
    }

    @Test
    fun `the payload of a decoded ok envelope reads the data Ari received`() {
        val sent = AriToolResult.ok { putString("color", "blue") }

        val read = AriToolResult.fromJson(sent.toJson())

        assertEquals("blue", (read as AriToolResult.Ok).payload.string("color"))
    }

    @Test
    fun `a failure with only text keeps a null code`() {
        val failure = AriToolResult.fromJson(AriToolResult.error("no").toJson())

        assertNull((failure as AriToolResult.Failure).code)
        assertEquals("no", failure.message)
    }

    /**
     * A later SDK adds a result kind. This build must report that as a coded failure, so the
     * new kind needs no version bump on either side.
     */
    @Test
    fun `an unknown kind reads as a coded failure, not a parse failure`() {
        val fromTheFuture = """{"ok":true,"kind":"pending_intent","intent":"content://x"}"""

        val failure = AriToolResult.fromJson(fromTheFuture) as AriToolResult.Failure

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
        assertEquals("the app returned a result Ari cannot read", failure.message)
    }

    @Test
    fun `a launch envelope names its kind and carries the spoken text`() {
        val result = AriToolResult.launch(pendingIntent, "Opening work order 42", immutable = true)

        val envelope = envelope(result)
        assertTrue(envelope.getBoolean("ok"))
        assertEquals(
            AriToolsContract.RESULT_KIND_LAUNCH,
            envelope.getString(AriToolsContract.FIELD_RESULT_KIND),
        )
        assertEquals("Opening work order 42", envelope.getString("spoken"))
        assertFalse(envelope.toString(), envelope.has("data"))
    }

    @Test
    fun `an immutable pending intent reaches the launch result`() {
        val result = AriToolResult.launch(pendingIntent, "Opening", immutable = true)

        assertSame(pendingIntent, (result as AriToolResult.Launch).pendingIntent)
    }

    /**
     * FLAG_IMMUTABLE is what makes Ari's send-time intent ignored. A mutable PendingIntent lets
     * the sender fill in every extras key the creator left unset, so the SDK must not pass one on.
     */
    @Test
    fun `a mutable pending intent is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AriToolResult.launch(pendingIntent, "Opening", immutable = false)
        }

        assertTrue(error.message, error.message.orEmpty().contains("FLAG_IMMUTABLE"))
    }

    /**
     * PendingIntent.isImmutable() arrived in Android 12, and the SDK supports Android 11. A
     * launch nothing has checked must not reach Ari, so the SDK reports a coded failure instead.
     */
    @Test
    fun `a launch is refused when the platform cannot report immutability`() {
        val result = AriToolResult.launch(pendingIntent, "Opening", immutable = null)

        val failure = result as AriToolResult.Failure
        assertEquals(AriToolsContract.ERROR_CODE_UNAVAILABLE, failure.code)
        assertTrue(failure.message, failure.message.orEmpty().contains("immutable"))
    }

    /**
     * Build.VERSION.SDK_INT reads 0 on the JVM, so this pins the guard that turns an unreadable
     * platform into the refusal above. The isImmutable() call itself only runs on a device.
     */
    @Test
    fun `immutability reads as unknown below android 12`() {
        assertNull(pendingIntent.immutableOrNull())
        assertEquals(
            AriToolsContract.ERROR_CODE_UNAVAILABLE,
            (AriToolResult.launch(pendingIntent, "Opening") as AriToolResult.Failure).code,
        )
    }

    @Test
    fun `a launch without spoken text is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AriToolResult.launch(pendingIntent, " ", immutable = true)
        }

        assertTrue(error.message, error.message.orEmpty().contains("spoken text"))
    }

    @Test
    fun `a launch envelope round trips with the intent that crossed binder`() {
        val payload = AriToolResult.launch(pendingIntent, "Opening", immutable = true).toJson()

        val launch = AriToolResult.fromJson(payload, pendingIntent) as AriToolResult.Launch

        assertSame(pendingIntent, launch.pendingIntent)
        assertEquals("Opening", launch.spoken)
    }

    @Test
    fun `a launch envelope with no intent next to it reads as a coded failure`() {
        val payload = AriToolResult.launch(pendingIntent, "Opening", immutable = true).toJson()

        val failure = AriToolResult.fromJson(payload) as AriToolResult.Failure

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
    }

    @Test
    fun `a launch envelope with no spoken text reads as a coded failure`() {
        val payload = """{"ok":true,"kind":"launch"}"""

        val failure = AriToolResult.fromJson(payload, pendingIntent) as AriToolResult.Failure

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
    }

    @Test
    fun `an envelope with no kind reads as a coded failure`() {
        val failure = AriToolResult.fromJson("""{"ok":true,"data":{}}""") as AriToolResult.Failure

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
    }

    @Test
    fun `text that is not an envelope reads as a coded failure`() {
        val failure = AriToolResult.fromJson("not json") as AriToolResult.Failure

        assertEquals(AriToolsContract.ERROR_CODE_APP_ERROR, failure.code)
    }

    /**
     * org.json rejects `NaN` and the infinities as it stores them, so a caller must map such
     * a value itself. The service replaces the throw with an error envelope, so the provider
     * process survives.
     */
    @Test
    fun `a non-finite number is rejected`() {
        assertThrows(JSONException::class.java) {
            AriToolResult.ok { putNumber("x", Double.NaN) }
        }
        assertThrows(JSONException::class.java) {
            AriToolResult.ok { putList("x") { addNumber(Double.POSITIVE_INFINITY) } }
        }
    }

    /**
     * The payload accessors take one JSON type each, so a `List`, a `Map`, a `LocalDate` or a
     * `File` has no accessor to reach the payload through. No test can run a compile error, so
     * this test pins the parameter types that create it.
     */
    @Test
    fun `the payload accessors accept only JSON primitives`() {
        val accepted = AriToolPayload::class.java.declaredMethods
            .filter { method -> method.name.startsWith("put") && !method.isSynthetic }
            .filter { method -> Modifier.isPublic(method.modifiers) }
            .associate { method -> method.name to method.parameterTypes.last() }

        assertEquals(
            mapOf(
                "putString" to String::class.java,
                "putInt" to Int::class.javaObjectType,
                "putNumber" to Double::class.javaObjectType,
                "putBool" to Boolean::class.javaObjectType,
                "putObject" to Function1::class.java,
                "putList" to Function1::class.java,
            ),
            accepted,
        )
    }
}
