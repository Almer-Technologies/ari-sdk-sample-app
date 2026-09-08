package com.example.aridemo

import com.ari_os.ari.sdk.AriToolArg
import com.ari_os.ari.sdk.AriToolDeclarationFile
import com.ari_os.ari.sdk.AriToolsContract
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the generated declaration against the real constants in
 * [AriToolsContract], not against what an earlier hand-written asset happened
 * to contain.
 *
 * [AriToolsAssetTest] proves the committed file matches the code. This proves
 * the file the code produces is one the current contract accepts: every cap,
 * the name pattern, the declaration and protocol versions, the capability set,
 * and the shape of every argument.
 *
 * The file is read twice on purpose. Once through
 * [AriToolDeclarationFile.serializer()], which is the type the Ari host decodes
 * the asset with, so a key the host cannot read fails here. Once as raw JSON,
 * so the assertions are about the bytes on disk rather than about the writer's
 * own round-trip.
 */
class AriToolsDeclarationContractTest {

    private val decoded: AriToolDeclarationFile =
        Json.decodeFromString(AriToolDeclarationFile.serializer(), ASSET_TEXT)

    private val raw = JSONObject(ASSET_TEXT)

    @Test
    fun `the asset decodes through the type the host reads it with`() {
        assertEquals(AriToolService().tools().declarations, decoded.tools)
    }

    @Test
    fun `the declaration version is the one this contract defines`() {
        assertEquals(AriToolsContract.DECLARATION_VERSION, decoded.declarationVersion)
        assertTrue(
            "version ${decoded.declarationVersion} is outside " +
                "${AriToolsContract.SUPPORTED_DECLARATION_VERSIONS}",
            decoded.declarationVersion in AriToolsContract.SUPPORTED_DECLARATION_VERSIONS,
        )
    }

    /** A file that omits the key is rejected whole, so it must be present on disk. */
    @Test
    fun `the declaration version is written explicitly`() {
        assertTrue(
            ASSET_TEXT,
            raw.has(AriToolsContract.FIELD_DECLARATION_VERSION),
        )
    }

    @Test
    fun `the host speaks the protocol version the asset declares`() {
        assertEquals(AriToolsContract.PROTOCOL_VERSION, decoded.protocolVersion)
        assertTrue(
            "protocol ${decoded.protocolVersion} is below " +
                "${AriToolsContract.MIN_SUPPORTED_PROTOCOL_VERSION}",
            AriToolsContract.speaksSupportedProtocol(decoded.protocolVersion),
        )
    }

    /** Every declared capability must be one the host can route, or it is dead weight. */
    @Test
    fun `every declared capability is one the host routes`() {
        val declared = decoded.capabilities
        assertTrue("the asset declares no capabilities", declared != null)

        assertEquals(
            declared?.toSet(),
            AriToolsContract.routedCapabilities(decoded.protocolVersion, declared),
        )
        assertTrue(
            "unknown capabilities: ${declared.orEmpty().toSet() - AriToolsContract.CAPABILITIES}",
            AriToolsContract.CAPABILITIES.containsAll(declared.orEmpty()),
        )
    }

    @Test
    fun `the asset declares no more tools than a provider may`() {
        assertTrue(
            "${decoded.tools.size} tools, cap is ${AriToolsContract.MAX_TOOLS_PER_PROVIDER}",
            decoded.tools.size <= AriToolsContract.MAX_TOOLS_PER_PROVIDER,
        )
        assertTrue("the asset declares no tools", decoded.tools.isNotEmpty())
    }

    @Test
    fun `every tool and arg name matches the contract's pattern`() {
        decoded.tools.forEach { tool ->
            assertTrue(
                "tool name '${tool.name}' does not match " +
                    AriToolsContract.TOOL_NAME_REGEX.pattern,
                AriToolsContract.TOOL_NAME_REGEX.matches(tool.name),
            )
            tool.args.forEach { arg ->
                assertTrue(
                    "arg name '${tool.name}.${arg.name}' does not match " +
                        AriToolsContract.TOOL_NAME_REGEX.pattern,
                    AriToolsContract.TOOL_NAME_REGEX.matches(arg.name),
                )
            }
        }
    }

    @Test
    fun `no tool or arg name is declared twice`() {
        assertEquals(
            decoded.tools.map { tool -> tool.name },
            decoded.tools.map { tool -> tool.name }.distinct(),
        )
        decoded.tools.forEach { tool ->
            val names = tool.args.map { arg -> arg.name }
            assertEquals("tool '${tool.name}' repeats an arg", names, names.distinct())
        }
    }

    @Test
    fun `every description is present and inside the length cap`() {
        decoded.tools.forEach { tool ->
            assertTrue("tool '${tool.name}' has no description", tool.description.isNotEmpty())
            assertTrue(
                "tool '${tool.name}': description is ${tool.description.length} chars, " +
                    "cap is ${AriToolsContract.MAX_DESCRIPTION_LENGTH}",
                tool.description.length <= AriToolsContract.MAX_DESCRIPTION_LENGTH,
            )
            tool.args.forEach { arg ->
                assertTrue(
                    "arg '${tool.name}.${arg.name}': description is " +
                        "${arg.description.length} chars, cap is " +
                        AriToolsContract.MAX_DESCRIPTION_LENGTH,
                    arg.description.length <= AriToolsContract.MAX_DESCRIPTION_LENGTH,
                )
            }
        }
    }

    /**
     * The cloud pins the five arg keys and rejects any other, and only an enum
     * carries `values`. Read straight off the bytes, per arg.
     */
    @Test
    fun `every arg on disk has the flat shape the cloud accepts`() {
        val tools = raw.getJSONArray("tools")
        for (t in 0 until tools.length()) {
            val tool = tools.getJSONObject(t)
            val name = tool.getString("name")
            val args = tool.optJSONArray("args") ?: continue
            for (a in 0 until args.length()) {
                val arg = args.getJSONObject(a)
                val keys = arg.keys().asSequence().toSet()
                assertTrue(
                    "arg '$name.${arg.getString("name")}' has unexpected keys: " +
                        "${keys - ARG_KEYS}",
                    ARG_KEYS.containsAll(keys),
                )
                assertTrue("arg in '$name' has no type", arg.has("type"))
            }
        }
    }

    /** Every declared type must decode to a type the SDK models, enum values included. */
    @Test
    fun `enum args carry values and no other type does`() {
        decoded.tools.flatMap { tool -> tool.args }.forEach { arg ->
            when (arg) {
                is AriToolArg.EnumArg -> assertTrue(
                    "enum arg '${arg.name}' has no values",
                    arg.values.isNotEmpty(),
                )

                else -> assertNull(
                    "arg '${arg.name}' is not an enum but carries values",
                    valuesOnDisk(arg.name),
                )
            }
        }
    }

    /** Ari takes the package from the installed APK, so a declared one is a second truth. */
    @Test
    fun `the asset carries no package id`() {
        assertTrue(ASSET_TEXT, !raw.has("package"))
    }

    private fun valuesOnDisk(argName: String): Any? {
        val tools = raw.getJSONArray("tools")
        for (t in 0 until tools.length()) {
            val args = tools.getJSONObject(t).optJSONArray("args") ?: continue
            for (a in 0 until args.length()) {
                val arg = args.getJSONObject(a)
                if (arg.getString("name") == argName) return arg.opt("values")
            }
        }
        return null
    }

    private companion object {
        val ARG_KEYS = setOf("name", "type", "values", "required", "description")

        val ASSET_TEXT: String = File(
            System.getProperty("ari.tools.assetsDir")
                ?: error("ari.tools.assetsDir is unset — run this through gradle"),
            AriToolsContract.DECLARATION_ASSET,
        ).readText()
    }
}
