package com.ari_os.ari.sdk

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles a regex with ICU, which rejects patterns the host jvm accepts,
 * so no unit test covers the checks below. Each case must also match with the
 * pattern it compiles, or a pattern that matches nothing still passes.
 */
class AriToolDeclarationInstrumentedTest {

    private fun openWorkOrder(uri: String) = AriToolDeclaration(
        name = "open_work_order",
        description = "Opens the work order with this number.",
        presentsUi = true,
        uri = uri,
        args = listOf(AriToolArg.IntArg(name = "number", required = true)),
    )

    @Test
    fun `an arg name is checked on the device engine`() {
        assertEquals("color", AriToolArg.EnumArg("color", listOf("red")).name)

        val error = assertThrows(IllegalArgumentException::class.java) {
            AriToolArg.EnumArg("Color", listOf("red"))
        }
        assertTrue(error.message, error.message.orEmpty().contains("must match"))
    }

    @Test
    fun `a uri placeholder is matched on the device engine`() {
        assertEquals("hpfield://order/{number}", openWorkOrder("hpfield://order/{number}").uri)

        val error = assertThrows(IllegalArgumentException::class.java) {
            openWorkOrder("hpfield://order/{ordinal}")
        }
        assertTrue(error.message, error.message.orEmpty().contains("not a declared arg"))
    }

    @Test
    fun `a named free text arg is matched against the placeholders on the device engine`() {
        val openRoom = AriToolDeclaration(
            name = "open_room",
            description = "Opens the room with this id.",
            presentsUi = true,
            uri = "aridemo://room/{room_id}",
            freeTextUriArgs = listOf("room_id"),
            args = listOf(AriToolArg.StringArg(name = "room_id", required = true)),
        )

        assertEquals(listOf("room_id"), openRoom.freeTextUriArgs)

        val error = assertThrows(IllegalArgumentException::class.java) {
            openRoom.copy(uri = "aridemo://room")
        }
        assertTrue(error.message, error.message.orEmpty().contains("the uri does not fill"))
    }

    @Test
    fun `a uri scheme is matched on the device engine`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            openWorkOrder("order/{number}")
        }
        assertTrue(error.message, error.message.orEmpty().contains("needs a literal scheme"))
    }

    @Test
    fun `a declaration asset decodes on the device engine`() {
        val asset = """
            {
              "declarationVersion": ${AriToolsContract.DECLARATION_VERSION},
              "tools": [
                {
                  "name": "open_work_order",
                  "description": "Opens the work order with this number.",
                  "presentsUi": true,
                  "uri": "hpfield://order/{number}",
                  "args": [{ "name": "number", "type": "int", "required": true }]
                }
              ]
            }
        """.trimIndent()

        val file = Json.decodeFromString(AriToolDeclarationFile.serializer(), asset)

        assertEquals(openWorkOrder("hpfield://order/{number}"), file.tools.single())
    }
}
