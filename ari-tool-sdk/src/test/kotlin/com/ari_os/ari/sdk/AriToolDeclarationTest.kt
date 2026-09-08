package com.ari_os.ari.sdk

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AriToolDeclarationTest {

    private fun colorArg() = AriToolArg.EnumArg(
        name = "color",
        values = listOf("red", "green", "blue"),
        required = true,
        description = "The colour to change the circle to.",
    )

    private fun setCircleColor() = AriToolDeclaration(
        name = "set_circle_color",
        description = "Sets the colour of the circle shown in the app.",
        confirm = false,
        args = listOf(colorArg()),
    )

    private fun openWorkOrder(
        uri: String? = "hpfield://order/{number}",
        presentsUi: Boolean = true,
        args: List<AriToolArg> = listOf(
            AriToolArg.IntArg(name = "number", required = true),
        ),
    ) = AriToolDeclaration(
        name = "open_work_order",
        description = "Opens the work order with this number.",
        presentsUi = presentsUi,
        uri = uri,
        args = args,
    )

    private fun everyArgType() = AriToolDeclaration(
        name = "probe",
        description = "Probe.",
        confirm = true,
        args = listOf(
            AriToolArg.StringArg(name = "text"),
            AriToolArg.IntArg(name = "count", required = true),
            AriToolArg.NumberArg(name = "ratio", description = "A ratio."),
            AriToolArg.BoolArg(name = "loud", required = true, description = "Loud?"),
            AriToolArg.EnumArg(
                name = "color",
                values = listOf("red", "green"),
                required = true,
                description = "The colour.",
            ),
        ),
    )

    @Test
    fun `every arg type encodes as one flat object with the same keys as before`() {
        assertEquals(
            EVERY_ARG_TYPE_WIRE,
            Json.encodeToString(AriToolDeclaration.serializer(), everyArgType()),
        )
    }

    @Test
    fun `every arg type decodes from the wire object it encodes to`() {
        assertEquals(everyArgType(), decodeTool(EVERY_ARG_TYPE_WIRE))
    }

    @Test
    fun `non enum args omit values even when defaults are encoded`() {
        val encoded = WITH_DEFAULTS.encodeToString(AriToolDeclaration.serializer(), everyArgType())

        assertEquals(EVERY_ARG_TYPE_WIRE_WITH_DEFAULTS, encoded)
    }

    /**
     * The host strips and rewrites fields by wire key before it decodes, so the descriptor has
     * to name every key even though a hand-written serializer builds it.
     */
    @Test
    fun `the arg descriptor names every wire key`() {
        val descriptor = AriToolArg.serializer().descriptor

        listOf("name", "type", "values", "required", "description").forEach { key ->
            assertTrue(key, descriptor.getElementIndex(key) >= 0)
        }
    }

    @Test
    fun `every arg type keeps its documented wire string`() {
        assertEquals(AriToolArg.StringArg(name = "a"), decodeArgOfType("string"))
        assertEquals(AriToolArg.IntArg(name = "a"), decodeArgOfType("int"))
        assertEquals(AriToolArg.NumberArg(name = "a"), decodeArgOfType("number"))
        assertEquals(AriToolArg.BoolArg(name = "a"), decodeArgOfType("bool"))
        assertEquals(
            AriToolArg.EnumArg(name = "a", values = listOf("x")),
            decodeArg("""{"name":"a","type":"enum","values":["x"]}"""),
        )
    }

    @Test
    fun `an unknown arg type is rejected at decode`() {
        val error = assertThrows(SerializationException::class.java) { decodeArgOfType("date") }

        assertTrue(error.message, error.message.orEmpty().contains("date"))
    }

    @Test
    fun `a rejected arg type is cut short so it cannot flood the log`() {
        val type = "d".repeat(200)

        val error = assertThrows(SerializationException::class.java) { decodeArgOfType(type) }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("d".repeat(MAX_ECHOED)))
        assertFalse(message, message.contains("d".repeat(MAX_ECHOED + 1)))
    }

    @Test
    fun `an arg name that breaks the regex is rejected`() {
        assertArgRejected(
            "arg name 'Color' must match",
            """{"name":"Color","type":"string"}""",
        ) { AriToolArg.StringArg(name = "Color") }
    }

    @Test
    fun `an enum arg without values is rejected`() {
        assertArgRejected(
            "arg 'color': type enum needs values",
            """{"name":"color","type":"enum"}""",
        ) { AriToolArg.EnumArg(name = "color", values = emptyList()) }
    }

    @Test
    fun `an enum arg with an empty values list is rejected`() {
        assertArgRejected(
            "arg 'color': type enum needs values",
            """{"name":"color","type":"enum","values":[]}""",
        ) { AriToolArg.EnumArg(name = "color", values = emptyList()) }
    }

    @Test
    fun `a non enum arg that carries values fails to decode`() {
        val error = assertThrows(SerializationException::class.java) {
            decodeArg("""{"name":"note","type":"string","values":["red"]}""")
        }

        assertTrue(error.message, error.message.orEmpty().contains("only type enum takes values"))
    }

    @Test
    fun `a blank enum value is rejected`() {
        assertArgRejected(
            "arg 'color': enum values must not be blank",
            """{"name":"color","type":"enum","values":["red"," "]}""",
        ) { AriToolArg.EnumArg(name = "color", values = listOf("red", " ")) }
    }

    @Test
    fun `a repeated enum value is rejected`() {
        assertArgRejected(
            "arg 'color': enum values must not repeat",
            """{"name":"color","type":"enum","values":["red","red"]}""",
        ) { AriToolArg.EnumArg(name = "color", values = listOf("red", "red")) }
    }

    @Test
    fun `an arg description over the cap is rejected`() {
        assertArgRejected(
            "arg 'note': description over $MAX_DESCRIPTION chars",
            """{"name":"note","type":"string","description":"$TOO_LONG_DESCRIPTION"}""",
        ) { AriToolArg.StringArg(name = "note", description = TOO_LONG_DESCRIPTION) }
    }

    @Test
    fun `an arg description at the cap is accepted`() {
        val description = "d".repeat(AriToolsContract.MAX_DESCRIPTION_LENGTH)
        val arg = AriToolArg.StringArg(name = "note", description = description)

        assertEquals(description, arg.description)
    }

    @Test
    fun `an arg without a name fails to decode`() {
        val error = assertThrows(SerializationException::class.java) {
            decodeArg("""{"type":"string"}""")
        }

        assertTrue(error.message, error.message.orEmpty().contains("name"))
    }

    @Test
    fun `a tool name that breaks the regex is rejected`() {
        assertToolRejected(
            "tool name 'Set_Color' must match",
            """{"name":"Set_Color","description":"Does something."}""",
        ) { AriToolDeclaration(name = "Set_Color", description = "Does something.") }
    }

    @Test
    fun `an empty tool description is rejected`() {
        assertToolRejected(
            "tool 'take_note': description is required",
            """{"name":"take_note","description":""}""",
        ) { AriToolDeclaration(name = "take_note", description = "") }
    }

    @Test
    fun `a tool description over the cap is rejected`() {
        assertToolRejected(
            "tool 'take_note': description over $MAX_DESCRIPTION chars",
            """{"name":"take_note","description":"$TOO_LONG_DESCRIPTION"}""",
        ) { AriToolDeclaration(name = "take_note", description = TOO_LONG_DESCRIPTION) }
    }

    @Test
    fun `a tool without a description fails to decode`() {
        val error = assertThrows(SerializationException::class.java) {
            decodeTool("""{"name":"take_note"}""")
        }

        assertTrue(error.message, error.message.orEmpty().contains("description"))
    }

    @Test
    fun `a rejected name is cut short so it cannot flood the log`() {
        val name = "T".repeat(200)

        val error = assertThrows(IllegalArgumentException::class.java) {
            AriToolDeclaration(name = name, description = "Does something.")
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("T".repeat(MAX_ECHOED)))
        assertFalse(message, message.contains("T".repeat(MAX_ECHOED + 1)))
    }

    @Test
    fun `the documented declaration file decodes field for field`() {
        val expected = AriToolDeclarationFile(
            declarationVersion = 1,
            label = LABEL,
            tools = listOf(setCircleColor()),
        )
        assertEquals(expected, decodeFile(DOCUMENTED_FILE))
    }

    @Test
    fun `a declaration file round trips through the documented wire keys`() {
        val decoded = decodeFile(DOCUMENTED_FILE)
        val encoded = Json.encodeToString(AriToolDeclarationFile.serializer(), decoded)
        assertTrue(encoded, encoded.contains("\"declarationVersion\":1"))
        assertTrue(encoded, encoded.contains("\"label\":\"$LABEL\""))
        assertTrue(encoded, !encoded.contains("\"package\""))
        assertEquals(decoded, decodeFile(encoded))
    }

    @Test
    fun `a file that names no protocol version and no capabilities reads as the implied ones`() {
        val decoded = decodeFile("""{"declarationVersion":1,"label":"$LABEL","tools":[]}""")

        assertEquals(AriToolsContract.IMPLIED_PROTOCOL_VERSION, decoded.protocolVersion)
        assertNull(decoded.capabilities)
    }

    @Test
    fun `the protocol version and the capabilities round trip through their wire keys`() {
        val file = AriToolDeclarationFile(
            declarationVersion = AriToolsContract.DECLARATION_VERSION,
            protocolVersion = AriToolsContract.PROTOCOL_VERSION,
            capabilities = listOf(AriToolsContract.CAPABILITY_CANCEL),
            label = LABEL,
        )

        val encoded = Json.encodeToString(AriToolDeclarationFile.serializer(), file)

        assertTrue(encoded, encoded.contains(""""protocolVersion":1"""))
        assertTrue(encoded, encoded.contains(""""capabilities":["cancel"]"""))
        assertEquals(file, decodeFile(encoded))
    }

    @Test
    fun `an empty capability list stays empty and does not become silence`() {
        val file = AriToolDeclarationFile(
            declarationVersion = AriToolsContract.DECLARATION_VERSION,
            capabilities = emptyList(),
        )

        val encoded = Json.encodeToString(AriToolDeclarationFile.serializer(), file)

        assertTrue(encoded, encoded.contains(""""capabilities":[]"""))
        assertEquals(emptyList<String>(), decodeFile(encoded).capabilities)
    }

    @Test
    fun `the protocol version is written even when it equals the implied one`() {
        val file = AriToolDeclarationFile(
            declarationVersion = AriToolsContract.DECLARATION_VERSION,
            protocolVersion = AriToolsContract.IMPLIED_PROTOCOL_VERSION,
        )

        val encoded = Json.encodeToString(AriToolDeclarationFile.serializer(), file)

        assertTrue(encoded, encoded.contains(""""protocolVersion":1"""))
    }

    @Test
    fun `a declaration file without a version is rejected, not read as version 1`() {
        val error = assertThrows(SerializationException::class.java) {
            decodeFile("""{"label":"$LABEL","tools":[]}""")
        }
        val message = error.message.orEmpty()
        assertTrue(message, message.contains("declarationVersion"))
    }

    @Test
    fun `a tool without a name fails only its own element`() {
        assertEquals(setCircleColor(), decodeTool(encodedSetCircleColor()))

        val error = assertThrows(SerializationException::class.java) {
            decodeTool(TOOL_WITHOUT_NAME)
        }

        assertTrue(error.message, error.message.orEmpty().contains("name"))
    }

    @Test
    fun `an arg without a type fails only its own element`() {
        assertEquals(setCircleColor(), decodeTool(encodedSetCircleColor()))

        val error = assertThrows(SerializationException::class.java) {
            decodeTool(TOOL_WITH_TYPELESS_ARG)
        }

        assertTrue(error.message, error.message.orEmpty().contains("type"))
    }

    @Test
    fun `one bad tool fails the whole file, so the host must decode tool by tool`() {
        val json = fileWithTools(encodedSetCircleColor(), TOOL_WITHOUT_NAME)

        assertThrows(SerializationException::class.java) { decodeFile(json) }
    }

    @Test
    fun `the documented uri tool decodes field for field`() {
        assertEquals(openWorkOrder(), decodeTool(DOCUMENTED_URI_TOOL))
    }

    @Test
    fun `a uri tool encodes its two new keys and omits them everywhere else`() {
        val withUri = Json.encodeToString(AriToolDeclaration.serializer(), openWorkOrder())
        assertTrue(withUri, withUri.contains(""""presentsUi":true"""))
        assertTrue(withUri, withUri.contains(""""uri":"hpfield://order/{number}""""))

        val withoutUri = Json.encodeToString(AriToolDeclaration.serializer(), setCircleColor())
        assertFalse(withoutUri, withoutUri.contains("uri"))
        assertFalse(withoutUri, withoutUri.contains("presentsUi"))
    }

    /** A uri tool opens a screen, so the declaration must say it returns no data. */
    @Test
    fun `a uri tool that does not present ui is rejected`() {
        assertToolRejected(
            "tool 'open_work_order': a uri tool returns no data, so presentsUi must be true",
            DOCUMENTED_URI_TOOL.replace(""""presentsUi": true""", """"presentsUi": false"""),
        ) { openWorkOrder(presentsUi = false) }
    }

    @Test
    fun `a placeholder that names no declared arg is rejected`() {
        assertToolRejected(
            "tool 'open_work_order': uri names 'ordinal', which is not a declared arg",
            DOCUMENTED_URI_TOOL.replace("{number}", "{ordinal}"),
        ) { openWorkOrder(uri = "hpfield://order/{ordinal}") }
    }

    @Test
    fun `a uri tool with no args at all rejects its placeholder`() {
        assertToolRejected(
            "uri names 'number', which is not a declared arg",
            """{"name":"open_work_order","description":"Opens it.","presentsUi":true,""" +
                """"uri":"hpfield://order/{number}"}""",
        ) { openWorkOrder(args = emptyList()) }
    }

    @Test
    fun `a required arg the uri leaves out is rejected`() {
        assertToolRejected(
            "tool 'open_work_order': uri leaves out required arg 'number'",
            DOCUMENTED_URI_TOOL.replace("order/{number}", "order"),
        ) { openWorkOrder(uri = "hpfield://order") }
    }

    @Test
    fun `an optional arg the uri leaves out is accepted`() {
        val tool = openWorkOrder(
            args = listOf(
                AriToolArg.IntArg(name = "number", required = true),
                AriToolArg.BoolArg(name = "readonly"),
            ),
        )

        assertEquals("hpfield://order/{number}", tool.uri)
    }

    @Test
    fun `a uri without a scheme is rejected`() {
        assertToolRejected(
            "tool 'open_work_order': uri needs a literal scheme, so no arg can choose one",
            DOCUMENTED_URI_TOOL.replace("hpfield://order/{number}", "order/{number}"),
        ) { openWorkOrder(uri = "order/{number}") }
    }

    /**
     * A placeholder scheme would let the model pick one, so the arg types would no longer say
     * where the uri points.
     */
    @Test
    fun `a uri whose scheme is a placeholder is rejected`() {
        assertToolRejected(
            "uri needs a literal scheme",
            DOCUMENTED_URI_TOOL.replace("hpfield:", "{number}:"),
        ) { openWorkOrder(uri = "{number}://order") }
    }

    @Test
    fun `a uri that does not parse is rejected`() {
        assertToolRejected(
            "tool 'open_work_order': uri is not a uri template",
            DOCUMENTED_URI_TOOL.replace("hpfield://order/{number}", "hpfield://ord er/{number}"),
        ) { openWorkOrder(uri = "hpfield://ord er/{number}") }
    }

    @Test
    fun `a placeholder cut short cannot flood the log`() {
        val name = "n".repeat(200)

        val error = assertThrows(IllegalArgumentException::class.java) {
            openWorkOrder(uri = "hpfield://order/{$name}")
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("n".repeat(MAX_ECHOED)))
        assertFalse(message, message.contains("n".repeat(MAX_ECHOED + 1)))
    }

    @Test
    fun `an int arg fills a placeholder`() {
        assertFillsPlaceholder(AriToolArg.IntArg(name = "number", required = true))
    }

    @Test
    fun `a number arg fills a placeholder`() {
        assertFillsPlaceholder(AriToolArg.NumberArg(name = "ratio", required = true))
    }

    @Test
    fun `a bool arg fills a placeholder`() {
        assertFillsPlaceholder(AriToolArg.BoolArg(name = "readonly", required = true))
    }

    @Test
    fun `an enum arg fills a placeholder`() {
        assertFillsPlaceholder(
            AriToolArg.EnumArg(name = "site", values = listOf("north", "south"), required = true),
        )
    }

    /** The model writes the text, so a string in a placeholder is a value nothing bounds. */
    @Test
    fun `a string arg cannot fill a placeholder`() {
        assertToolRejected(
            "tool 'open_work_order': arg 'number' is free text, " +
                "so it cannot fill a uri placeholder",
            DOCUMENTED_URI_TOOL.replace(""""type": "int"""", """"type": "string""""),
        ) { openWorkOrder(args = listOf(AriToolArg.StringArg(name = "number", required = true))) }
    }

    @Test
    fun `a string arg the uri leaves out is accepted`() {
        val tool = openWorkOrder(
            args = listOf(
                AriToolArg.IntArg(name = "number", required = true),
                AriToolArg.StringArg(name = "note"),
            ),
        )

        assertEquals("hpfield://order/{number}", tool.uri)
    }

    @Test
    fun `a repeated placeholder is accepted`() {
        val tool = openWorkOrder(uri = "hpfield://order/{number}?title={number}")

        assertEquals("hpfield://order/{number}?title={number}", tool.uri)
    }

    private fun assertFillsPlaceholder(arg: AriToolArg) {
        val uri = "hpfield://order/{${arg.name}}"

        assertEquals(uri, openWorkOrder(uri = uri, args = listOf(arg)).uri)
    }

    private fun assertArgRejected(expected: String, json: String, build: () -> AriToolArg) {
        assertRejected(expected, build) { decodeArg(json) }
    }

    private fun assertToolRejected(
        expected: String,
        json: String,
        build: () -> AriToolDeclaration,
    ) {
        assertRejected(expected, build) { decodeTool(json) }
    }

    private fun assertRejected(expected: String, build: () -> Any, decode: () -> Any) {
        val fromConstructor = assertThrows(IllegalArgumentException::class.java) { build() }
        assertTrue(fromConstructor.message, fromConstructor.message.orEmpty().contains(expected))

        val fromDecode = assertThrows(IllegalArgumentException::class.java) { decode() }
        assertTrue(fromDecode.message, fromDecode.message.orEmpty().contains(expected))
    }

    private fun decodeFile(json: String) =
        Json.decodeFromString(AriToolDeclarationFile.serializer(), json)

    private fun decodeTool(json: String) =
        Json.decodeFromString(AriToolDeclaration.serializer(), json)

    private fun decodeArg(json: String) =
        Json.decodeFromString(AriToolArg.serializer(), json)

    private fun decodeArgOfType(wire: String) = decodeArg("""{"name":"a","type":"$wire"}""")

    private fun encodedSetCircleColor() =
        Json.encodeToString(AriToolDeclaration.serializer(), setCircleColor())

    private fun fileWithTools(vararg tools: String) = """
        {
          "label": "$LABEL",
          "declarationVersion": 1,
          "tools": [${tools.joinToString(",")}]
        }
    """.trimIndent()

    private companion object {
        val WITH_DEFAULTS = Json { encodeDefaults = true }

        const val LABEL = "Ari Demo"
        const val MAX_ECHOED = 32

        val MAX_DESCRIPTION = AriToolsContract.MAX_DESCRIPTION_LENGTH
        val TOO_LONG_DESCRIPTION = "d".repeat(MAX_DESCRIPTION + 1)

        const val TOOL_WITHOUT_NAME = """{"description":"Does something else."}"""

        val TOOL_WITH_TYPELESS_ARG = """
            {
              "name": "take_note",
              "description": "Takes a note.",
              "args": [{ "name": "note" }]
            }
        """.trimIndent()

        // The cloud pins these keys with extra='forbid', so an edit here is a
        // breaking change, not a fix.
        const val EVERY_ARG_TYPE_WIRE =
            """{"name":"probe","description":"Probe.","confirm":true,"args":[""" +
                """{"name":"text","type":"string"},""" +
                """{"name":"count","type":"int","required":true},""" +
                """{"name":"ratio","type":"number","description":"A ratio."},""" +
                """{"name":"loud","type":"bool","required":true,"description":"Loud?"},""" +
                """{"name":"color","type":"enum","values":["red","green"],"required":true,""" +
                """"description":"The colour."}]}"""

        const val EVERY_ARG_TYPE_WIRE_WITH_DEFAULTS =
            """{"name":"probe","description":"Probe.","confirm":true,"args":[""" +
                """{"name":"text","type":"string","required":false,"description":""},""" +
                """{"name":"count","type":"int","required":true,"description":""},""" +
                """{"name":"ratio","type":"number","required":false,"description":"A ratio."},""" +
                """{"name":"loud","type":"bool","required":true,"description":"Loud?"},""" +
                """{"name":"color","type":"enum","values":["red","green"],"required":true,""" +
                """"description":"The colour."}]}"""

        // A hand-written tool, with the keys in another order. The host reads a file
        // the writer did not produce, so this shape has to keep decoding.
        val DOCUMENTED_URI_TOOL = """
            {
              "name": "open_work_order",
              "description": "Opens the work order with this number.",
              "presentsUi": true,
              "uri": "hpfield://order/{number}",
              "args": [{ "name": "number", "type": "int", "required": true }]
            }
        """.trimIndent()

        // A hand-written file, with the keys in another order. The host reads a file
        // the writer did not produce, so this shape has to keep decoding.
        val DOCUMENTED_FILE = """
            {
              "label": "Ari Demo",
              "declarationVersion": 1,
              "tools": [
                {
                  "name": "set_circle_color",
                  "description": "Sets the colour of the circle shown in the app.",
                  "confirm": false,
                  "args": [
                    {
                      "name": "color",
                      "type": "enum",
                      "values": ["red", "green", "blue"],
                      "required": true,
                      "description": "The colour to change the circle to."
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
    }
}
