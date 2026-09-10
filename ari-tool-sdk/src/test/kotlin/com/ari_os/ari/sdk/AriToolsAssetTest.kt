package com.ari_os.ari.sdk

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AriToolsAssetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class DemoService : AriToolProviderService() {
        override fun tools(): AriToolRegistry = ariTools(label = LABEL) {
            tool("set_circle_color", "Sets the colour of the circle shown in the app.") {
                enum(
                    "color",
                    values = listOf("red", "green", "blue"),
                    description = "The colour to change the circle to.",
                    required = true,
                )
                handle { AriToolResult.ok() }
            }
        }
    }

    private fun setCircleColor() = ariTools(label = LABEL) {
        tool("set_circle_color", "Sets the colour of the circle shown in the app.") {
            enum(
                "color",
                values = listOf("red", "green", "blue"),
                description = "The colour to change the circle to.",
                required = true,
            )
            handle { AriToolResult.ok() }
        }
    }

    private fun bothKinds() = ariTools(label = LABEL) {
        tool("set_circle_color", "Sets the colour of the circle shown in the app.") {
            enum(
                "color",
                values = listOf("red", "green", "blue"),
                description = "The colour to change the circle to.",
                required = true,
            )
            handle { AriToolResult.ok() }
        }
        deeplink(
            "open_work_order",
            "Opens the work order with this number.",
            uri = "hpfield://order/{number}",
        ) {
            int("number", "The work order number.", required = true)
        }
    }

    private fun removeCircles() = ariTools(label = LABEL) {
        tool("remove_circles", "Removes the circles with these numbers.", confirm = true) {
            intList("numbers", description = "The circle numbers to remove.", required = true)
            handle { AriToolResult.ok() }
        }
    }

    private fun freeTextDeeplink() = ariTools(label = LABEL) {
        deeplink("open_room", "Opens the room with this id.", uri = "aridemo://room/{room_id}") {
            freeTextInUri("room_id", "The room id, as printed on the door.", required = true)
        }
    }

    private fun labelled(label: String) = ariTools(label = label) {
        tool("take_note", "Takes a note.") { handle { AriToolResult.ok() } }
    }

    // The label is the only declared text with no length rule, so it is the knob that
    // lands the asset on an exact byte count.
    private fun registryOfExactly(bytes: Int): AriToolRegistry {
        val fixed = AriToolsAsset.encode(labelled("")).toByteArray(Charsets.UTF_8).size
        return labelled("x".repeat(bytes - fixed))
    }

    private fun longCatalogue() = ariTools(label = LABEL) {
        repeat(CATALOGUE_TOOLS) { index ->
            val description = "x".repeat(AriToolsContract.MAX_DESCRIPTION_LENGTH)
            tool("take_note_%03d".format(index), description) {
                handle { AriToolResult.ok() }
            }
        }
    }

    @Test
    fun `the asset holds the fixed key order and ends with a newline`() {
        assertEquals(BOTH_KINDS_ASSET, AriToolsAsset.encode(bothKinds()))
    }

    /** The host decodes the asset with these very types, so the writer must feed them. */
    @Test
    fun `the asset decodes through the declaration types the host reads`() {
        val decoded = Json.decodeFromString(
            AriToolDeclarationFile.serializer(),
            AriToolsAsset.encode(bothKinds()),
        )

        val expected = AriToolDeclarationFile(
            declarationVersion = AriToolsContract.DECLARATION_VERSION,
            protocolVersion = AriToolsContract.PROTOCOL_VERSION,
            capabilities = AriToolsContract.CAPABILITIES.sorted(),
            label = LABEL,
            tools = bothKinds().declarations,
        )
        assertEquals(expected, decoded)
    }

    /**
     * `presentsUi` and `uri` carry @EncodeDefault(NEVER), so a tool that sets neither
     * writes the file the earlier format wrote, byte for byte.
     */
    @Test
    fun `a tool with neither new field writes the earlier format`() {
        val encoded = AriToolsAsset.encode(setCircleColor())

        assertEquals(WITHOUT_NEW_FIELDS_ASSET, encoded)
        assertFalse(encoded, encoded.contains("presentsUi"))
        assertFalse(encoded, encoded.contains("uri"))
    }

    @Test
    fun `a list arg round trips through the asset`() {
        val registry = removeCircles()

        val encoded = AriToolsAsset.encode(registry)

        assertEquals(LIST_ARG_ASSET, encoded)
        val decoded = Json.decodeFromString(AriToolDeclarationFile.serializer(), encoded)
        assertEquals(registry.declarations, decoded.tools)
    }

    /**
     * An older host rejects the type value and drops that one tool, so it never misreads
     * the file. The version sits on the file, so raising it would cost a provider every
     * tool it declares, including the ones that use no list.
     */
    @Test
    fun `a list arg does not move the declaration version`() {
        val encoded = AriToolsAsset.encode(removeCircles())

        assertTrue(encoded, encoded.contains("\"declarationVersion\": 2,"))
        assertEquals(2, AriToolsContract.DECLARATION_VERSION)
    }

    @Test
    fun `a free text deeplink round trips through the asset`() {
        val registry = freeTextDeeplink()

        val encoded = AriToolsAsset.encode(registry)

        assertTrue(encoded, encoded.contains(""""freeTextUriArgs": ["""))
        val decoded = Json.decodeFromString(AriToolDeclarationFile.serializer(), encoded)
        assertEquals(registry.declarations, decoded.tools)
    }

    /** Silence is not consent: an asset that omits the key opts nothing in. */
    @Test
    fun `an asset without the key decodes as naming no free text arg`() {
        val encoded = AriToolsAsset.encode(bothKinds())

        assertFalse(encoded, encoded.contains("freeTextUriArgs"))
        val decoded = Json.decodeFromString(AriToolDeclarationFile.serializer(), encoded)
        assertTrue(decoded.tools.all { tool -> tool.freeTextUriArgs.isEmpty() })
    }

    @Test
    fun `the asset declares every capability the SDK implements, in sorted order`() {
        val declared = Json.decodeFromString(
            AriToolDeclarationFile.serializer(),
            AriToolsAsset.encode(setCircleColor()),
        ).capabilities

        assertEquals(AriToolsContract.CAPABILITIES.sorted(), declared)
    }

    @Test
    fun `a registry without a label omits the key`() {
        val encoded = AriToolsAsset.encode(
            ariTools { tool("take_note", "Takes a note.") { handle { AriToolResult.ok() } } },
        )

        assertFalse(encoded, encoded.contains("label"))
        assertTrue(encoded, encoded.startsWith("{\n  \"declarationVersion\": 2,"))
    }

    @Test
    fun `an asset at the size cap encodes`() {
        val encoded = AriToolsAsset.encode(registryOfExactly(AriToolsContract.MAX_DECLARATION_BYTES))

        assertEquals(
            AriToolsContract.MAX_DECLARATION_BYTES,
            encoded.toByteArray(Charsets.UTF_8).size,
        )
    }

    @Test
    fun `an asset one byte over the size cap fails the build`() {
        val over = AriToolsContract.MAX_DECLARATION_BYTES + 1

        val error = assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.encode(registryOfExactly(over))
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains(AriToolsContract.DECLARATION_ASSET))
        assertTrue(message, message.contains("$over bytes"))
        assertTrue(message, message.contains("${AriToolsContract.MAX_DECLARATION_BYTES} bytes"))
    }

    @Test
    fun `a catalogue over the size cap writes no asset`() {
        val assets = folder.newFolder("assets")

        assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.writeTo(assets, longCatalogue())
        }

        assertFalse(File(assets, AriToolsContract.DECLARATION_ASSET).exists())
    }

    /** encode is the one choke point, so the size answers before the drift compare. */
    @Test
    fun `a catalogue over the size cap reports its size, not drift`() {
        val assets = folder.newFolder("assets")

        val error = assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.requireMatches(assets, longCatalogue())
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains("reads at most"))
        assertFalse(message, message.contains("does not match the tool registry"))
    }

    @Test
    fun `writeTo puts the asset at the fixed path and creates the folder`() {
        val assets = File(folder.root, "src/main/assets")

        val written = AriToolsAsset.writeTo(assets, bothKinds())

        assertEquals(AriToolsContract.DECLARATION_ASSET, written.name)
        assertEquals(assets, written.parentFile)
        assertEquals(BOTH_KINDS_ASSET, written.readText())
    }

    @Test
    fun `the file writeTo produced matches the registry it came from`() {
        val assets = folder.newFolder("assets")
        AriToolsAsset.writeTo(assets, bothKinds())

        AriToolsAsset.requireMatches(assets, bothKinds())
    }

    @Test
    fun `a tool added in code and not in the asset fails the check`() {
        val assets = folder.newFolder("assets")
        AriToolsAsset.writeTo(assets, setCircleColor())

        val error = assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.requireMatches(assets, bothKinds())
        }

        val message = error.message.orEmpty()
        assertTrue(message, message.contains(AriToolsContract.DECLARATION_ASSET))
        assertTrue(message, message.contains("AriToolsAsset.writeTo()"))
        assertTrue(message, message.contains("First difference on line"))
    }

    @Test
    fun `a description edited by hand in the asset fails the check`() {
        val assets = folder.newFolder("assets")
        val asset = AriToolsAsset.writeTo(assets, setCircleColor())
        asset.writeText(asset.readText().replace("Sets the colour", "Sets the color"))

        val error = assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.requireMatches(assets, setCircleColor())
        }

        assertTrue(error.message, error.message.orEmpty().contains("First difference on line 12"))
    }

    /** The check a partner writes reads the registry off their own service. */
    @Test
    fun `the asset of a service round trips through the writer and the check`() {
        val assets = folder.newFolder("assets")

        AriToolsAsset.writeTo(assets, DemoService().tools())

        assertEquals(WITHOUT_NEW_FIELDS_ASSET, File(assets, "ari_tools.json").readText())
        AriToolsAsset.requireMatches(assets, DemoService().tools())
    }

    @Test
    fun `a missing asset fails the check and says so`() {
        val error = assertThrows(IllegalStateException::class.java) {
            AriToolsAsset.requireMatches(folder.newFolder("assets"), setCircleColor())
        }

        assertTrue(error.message, error.message.orEmpty().contains("The file is missing."))
    }

    /** A checkout can store the asset with CRLF, which is not drift. */
    @Test
    fun `windows line endings still match`() {
        val assets = folder.newFolder("assets")
        val asset = AriToolsAsset.writeTo(assets, setCircleColor())
        asset.writeText(asset.readText().replace("\n", "\r\n"))

        AriToolsAsset.requireMatches(assets, setCircleColor())
    }

    private companion object {
        const val LABEL = "Ari Demo"

        // Enough tools to clear the cap: each one writes its name, its description and
        // its braces, so 300 chars of description alone already pass 64 KiB.
        const val CATALOGUE_TOOLS = 256

        // The host decodes these keys, so an edit here is a wire change, not a fix.
        // Must stay identical to the example in README.md.
        val WITHOUT_NEW_FIELDS_ASSET = """
            {
              "declarationVersion": 2,
              "protocolVersion": 1,
              "capabilities": [
                "cancel",
                "launch_result"
              ],
              "label": "Ari Demo",
              "tools": [
                {
                  "name": "set_circle_color",
                  "description": "Sets the colour of the circle shown in the app.",
                  "args": [
                    {
                      "name": "color",
                      "type": "enum",
                      "values": [
                        "red",
                        "green",
                        "blue"
                      ],
                      "required": true,
                      "description": "The colour to change the circle to."
                    }
                  ]
                }
              ]
            }
        """.trimIndent() + "\n"

        val LIST_ARG_ASSET = """
            {
              "declarationVersion": 2,
              "protocolVersion": 1,
              "capabilities": [
                "cancel",
                "launch_result"
              ],
              "label": "Ari Demo",
              "tools": [
                {
                  "name": "remove_circles",
                  "description": "Removes the circles with these numbers.",
                  "confirm": true,
                  "args": [
                    {
                      "name": "numbers",
                      "type": "int_list",
                      "required": true,
                      "description": "The circle numbers to remove."
                    }
                  ]
                }
              ]
            }
        """.trimIndent() + "\n"

        val BOTH_KINDS_ASSET = """
            {
              "declarationVersion": 2,
              "protocolVersion": 1,
              "capabilities": [
                "cancel",
                "launch_result"
              ],
              "label": "Ari Demo",
              "tools": [
                {
                  "name": "set_circle_color",
                  "description": "Sets the colour of the circle shown in the app.",
                  "args": [
                    {
                      "name": "color",
                      "type": "enum",
                      "values": [
                        "red",
                        "green",
                        "blue"
                      ],
                      "required": true,
                      "description": "The colour to change the circle to."
                    }
                  ]
                },
                {
                  "name": "open_work_order",
                  "description": "Opens the work order with this number.",
                  "presentsUi": true,
                  "uri": "hpfield://order/{number}",
                  "args": [
                    {
                      "name": "number",
                      "type": "int",
                      "required": true,
                      "description": "The work order number."
                    }
                  ]
                }
              ]
            }
        """.trimIndent() + "\n"
    }
}
