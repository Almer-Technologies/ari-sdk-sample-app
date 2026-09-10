package com.ari_os.ari.sdk

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AriToolRegistryTest {

    @Test
    fun `the list builders reach the declaration`() {
        val registry = ariTools {
            tool("remove_circles", "Removes circles.", confirm = true) {
                intList("numbers", "The numbers to remove.", required = true)
                stringList("tags", "The tags.")
                handle { AriToolResult.ok() }
            }
        }

        assertEquals(
            listOf(
                AriToolArg.IntListArg(
                    name = "numbers",
                    required = true,
                    description = "The numbers to remove.",
                ),
                AriToolArg.StringListArg(name = "tags", description = "The tags."),
            ),
            registry.declarations.single().args,
        )
    }

    @Test
    fun `a deeplink cannot fill a placeholder from a list arg`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools {
                deeplink("open_orders", "Opens orders.", uri = "hpfield://orders/{numbers}") {
                    intList("numbers", "The numbers.", required = true)
                }
            }
        }

        assertTrue(error.message, error.message.orEmpty().contains("arg 'numbers' is a list"))
    }

    @Test
    fun `a declared tool builds the declaration the types already define`() {
        val registry = ariTools(label = "Ari Demo") {
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

        val expected = AriToolDeclaration(
            name = "set_circle_color",
            description = "Sets the colour of the circle shown in the app.",
            args = listOf(
                AriToolArg.EnumArg(
                    name = "color",
                    values = listOf("red", "green", "blue"),
                    required = true,
                    description = "The colour to change the circle to.",
                ),
            ),
        )
        assertEquals(listOf(expected), registry.declarations)
        assertEquals("Ari Demo", registry.label)
    }

    @Test
    fun `each arg builder declares its own variant of the sealed type`() {
        val registry = ariTools {
            tool("probe", "Probe.", confirm = true) {
                string("text")
                int("count", required = true)
                number("ratio", description = "A ratio.")
                bool("loud", description = "Loud?", required = true)
                enum("color", values = listOf("red", "green"), required = true)
                handle { AriToolResult.ok() }
            }
        }

        assertEquals(
            listOf(
                AriToolArg.StringArg(name = "text"),
                AriToolArg.IntArg(name = "count", required = true),
                AriToolArg.NumberArg(name = "ratio", description = "A ratio."),
                AriToolArg.BoolArg(name = "loud", required = true, description = "Loud?"),
                AriToolArg.EnumArg(name = "color", values = listOf("red", "green"), required = true),
            ),
            registry.declarations.single().args,
        )
        assertTrue(registry.declarations.single().confirm)
    }

    @Test
    fun `an arg the declaration types reject is rejected by the builder too`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools {
                tool("probe", "Probe.") {
                    enum("color", values = emptyList())
                    handle { AriToolResult.ok() }
                }
            }
        }

        assertTrue(error.message, error.message.orEmpty().contains("type enum needs values"))
    }

    @Test
    fun `a handler runs with the call as its receiver`() = runTest {
        val registry = ariTools {
            tool("whoami", "Reports the request id.") {
                handle { args ->
                    AriToolResult.ok {
                        putString("request", requestId)
                        putString("color", args.string("color"))
                    }
                }
            }
        }

        val handler = requireNotNull(registry.find("whoami")?.handler)
        val result = handler(
            AriToolCall("req-1"),
            ToolArgs(JSONObject("""{"color":"red"}""")),
        )

        val data = JSONObject(result.toJson()).getJSONObject("data")
        assertEquals("req-1", data.getString("request"))
        assertEquals("red", data.getString("color"))
    }

    @Test
    fun `a tool without a handler is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools { tool("take_note", "Takes a note.") { string("note") } }
        }

        assertTrue(
            error.message,
            error.message.orEmpty().contains("tool 'take_note': no handler"),
        )
    }

    @Test
    fun `a tool with two handlers is rejected, so no handler is dropped in silence`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools {
                tool("take_note", "Takes a note.") {
                    handle { AriToolResult.ok() }
                    handle { AriToolResult.error("second") }
                }
            }
        }

        assertTrue(
            error.message,
            error.message.orEmpty().contains("tool 'take_note': handle is declared twice"),
        )
    }

    @Test
    fun `a deeplink presents ui and runs no code`() {
        val registry = ariTools {
            deeplink(
                "open_work_order",
                "Opens the work order with this number.",
                uri = "hpfield://order/{number}",
            ) {
                int("number", "The work order number.", required = true)
            }
        }

        val declaration = registry.declarations.single()
        assertEquals("hpfield://order/{number}", declaration.uri)
        assertTrue(declaration.presentsUi)
        assertNull(registry.find("open_work_order")?.handler)
    }

    @Test
    fun `a deeplink whose template names no declared arg is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools { deeplink("open_order", "Opens it.", uri = "hpfield://order/{number}") }
        }

        assertTrue(
            error.message,
            error.message.orEmpty().contains("uri names 'number', which is not a declared arg"),
        )
    }

    @Test
    fun `a deeplink lets a named free text arg fill a placeholder`() {
        val registry = ariTools {
            deeplink("open_room", "Opens the room with this id.", uri = "aridemo://room/{room_id}") {
                freeTextInUri("room_id", "The room id, as printed on the door.", required = true)
            }
        }

        val declaration = registry.declarations.single()
        assertEquals(listOf("room_id"), declaration.freeTextUriArgs)
        assertEquals(
            listOf(
                AriToolArg.StringArg(
                    name = "room_id",
                    required = true,
                    description = "The room id, as printed on the door.",
                ),
            ),
            declaration.args,
        )
    }

    @Test
    fun `a deeplink whose placeholder takes a plain string is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools {
                deeplink("open_room", "Opens the room with this id.", uri = "aridemo://room/{room_id}") {
                    string("room_id", required = true)
                }
            }
        }

        assertTrue(
            error.message,
            error.message.orEmpty().contains("arg 'room_id' is free text"),
        )
    }

    @Test
    fun `a tool declared twice is rejected`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ariTools {
                tool("take_note", "Takes a note.") { handle { AriToolResult.ok() } }
                deeplink("take_note", "Opens the notes.", uri = "hpfield://notes")
            }
        }

        assertTrue(
            error.message,
            error.message.orEmpty().contains("tool declared twice: take_note"),
        )
    }

    /** Nothing caps how many tools a provider declares. The declaration file size does. */
    @Test
    fun `a provider may declare many tools`() {
        val many = 64

        val registry = registryOf(many)

        assertEquals(many, registry.tools.size)
    }

    @Test
    fun `the registry keeps declaration order`() {
        val registry = ariTools {
            tool("second", "Second.") { handle { AriToolResult.ok() } }
            deeplink("first", "First.", uri = "hpfield://first")
        }

        assertEquals(listOf("second", "first"), registry.declarations.map { tool -> tool.name })
    }

    @Test
    fun `an unknown name finds no tool`() {
        val registry = ariTools {
            tool("take_note", "Takes a note.") { handle { AriToolResult.ok() } }
        }

        assertNull(registry.find("set_circle_color"))
    }

    private fun registryOf(tools: Int) = ariTools {
        repeat(tools) { index ->
            tool("tool_$index", "Tool $index.") { handle { AriToolResult.ok() } }
        }
    }
}
