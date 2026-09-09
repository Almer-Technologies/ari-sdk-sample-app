package com.ari_os.ari.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AriToolsContractTest {

    @Test
    fun `wire constants match the published contract`() {
        assertEquals("com.ari_os.ari.action.TOOL_PROVIDER", AriToolsContract.ACTION_TOOL_PROVIDER)
        assertEquals(
            "com.ari_os.ari.permission.BIND_TOOL_PROVIDER",
            AriToolsContract.PERMISSION_BIND_TOOL_PROVIDER
        )
        assertEquals("ari_tools.json", AriToolsContract.DECLARATION_ASSET)
        assertEquals("com.ari_os.ari.tools", AriToolsContract.META_DATA_TOOL_PROVIDER)
        assertEquals(2, AriToolsContract.DECLARATION_VERSION)
        assertEquals("declarationVersion", AriToolsContract.FIELD_DECLARATION_VERSION)
    }

    @Test
    fun `the availability channel matches the published contract`() {
        assertEquals("com.ari_os.ari.tools", AriToolsContract.AUTHORITY_TOOLS)
        assertEquals("set_available", AriToolsContract.METHOD_SET_AVAILABLE)
        assertEquals("available_tools", AriToolsContract.KEY_AVAILABLE_TOOLS)
        assertEquals("accepted", AriToolsContract.KEY_ACCEPTED)
        assertEquals("reason", AriToolsContract.KEY_REASON)
    }

    @Test
    fun `result kinds match the published contract`() {
        assertEquals("kind", AriToolsContract.FIELD_RESULT_KIND)
        assertEquals("ok", AriToolsContract.RESULT_KIND_OK)
        assertEquals("failure", AriToolsContract.RESULT_KIND_FAILURE)
        assertEquals("launch", AriToolsContract.RESULT_KIND_LAUNCH)
    }

    @Test
    fun `error codes match the published taxonomy`() {
        assertEquals("invalid_argument", AriToolsContract.ERROR_CODE_INVALID_ARGUMENT)
        assertEquals("unknown_tool", AriToolsContract.ERROR_CODE_UNKNOWN_TOOL)
        assertEquals("unavailable", AriToolsContract.ERROR_CODE_UNAVAILABLE)
        assertEquals("denied", AriToolsContract.ERROR_CODE_DENIED)
        assertEquals("cancelled", AriToolsContract.ERROR_CODE_CANCELLED)
        assertEquals("app_error", AriToolsContract.ERROR_CODE_APP_ERROR)
    }

    @Test
    fun `size caps stay well under the binder transaction buffer`() {
        val binderBufferBytes = 1024 * 1024
        val utf16CostPerAsciiByte = 2

        assertEquals(64 * 1024, AriToolsContract.MAX_RESULT_BYTES)
        assertEquals(8 * 1024, AriToolsContract.MAX_ARGS_BYTES)
        assertEquals(8, binderBufferBytes / (AriToolsContract.MAX_RESULT_BYTES * utf16CostPerAsciiByte))
        assertEquals(64, binderBufferBytes / (AriToolsContract.MAX_ARGS_BYTES * utf16CostPerAsciiByte))
    }

    @Test
    fun `declaration caps match the published contract`() {
        assertEquals(8, AriToolsContract.MAX_TOOLS_PER_PROVIDER)
        assertEquals(300, AriToolsContract.MAX_DESCRIPTION_LENGTH)
    }

    @Test
    fun `the host reads its own declaration version and every older one`() {
        val supported = AriToolsContract.SUPPORTED_DECLARATION_VERSIONS

        assertEquals(1, supported.first)
        assertEquals(AriToolsContract.DECLARATION_VERSION, supported.last)
        assertFalse(AriToolsContract.DECLARATION_VERSION + 1 in supported)
    }

    @Test
    fun `tool name regex accepts snake case and rejects everything else`() {
        assertTrue(AriToolsContract.TOOL_NAME_REGEX.matches("set_circle_color"))
        assertFalse(AriToolsContract.TOOL_NAME_REGEX.matches("Set_Color"))
        assertFalse(AriToolsContract.TOOL_NAME_REGEX.matches("1color"))
        assertFalse(AriToolsContract.TOOL_NAME_REGEX.matches("set-color"))
        assertFalse(AriToolsContract.TOOL_NAME_REGEX.matches(""))
        assertFalse(AriToolsContract.TOOL_NAME_REGEX.matches("t".repeat(33)))
    }

    @Test
    fun `the protocol floor and the capability names match the published contract`() {
        assertEquals(1, AriToolsContract.PROTOCOL_VERSION)
        assertEquals(1, AriToolsContract.MIN_SUPPORTED_PROTOCOL_VERSION)
        assertEquals(1, AriToolsContract.IMPLIED_PROTOCOL_VERSION)
        assertEquals("protocolVersion", AriToolsContract.FIELD_PROTOCOL_VERSION)
        assertEquals("capabilities", AriToolsContract.FIELD_CAPABILITIES)
        assertEquals("cancel", AriToolsContract.CAPABILITY_CANCEL)
        assertEquals("launch_result", AriToolsContract.CAPABILITY_LAUNCH_RESULT)
        assertEquals(setOf("cancel", "launch_result"), AriToolsContract.CAPABILITIES)
    }

    @Test
    fun `the protocol version is a floor, so a newer provider is still accepted`() {
        assertFalse(AriToolsContract.speaksSupportedProtocol(0))
        assertTrue(
            AriToolsContract.speaksSupportedProtocol(AriToolsContract.MIN_SUPPORTED_PROTOCOL_VERSION)
        )
        assertTrue(AriToolsContract.speaksSupportedProtocol(AriToolsContract.PROTOCOL_VERSION + 1))
    }

    @Test
    fun `a declaration that names no capabilities gets the ones its version implies`() {
        assertEquals(
            AriToolsContract.CAPABILITIES,
            AriToolsContract.routedCapabilities(AriToolsContract.IMPLIED_PROTOCOL_VERSION, null),
        )
    }

    @Test
    fun `an empty capability list is not the same as naming none`() {
        assertEquals(
            emptySet<String>(),
            AriToolsContract.routedCapabilities(AriToolsContract.PROTOCOL_VERSION, emptyList()),
        )
        assertEquals(
            AriToolsContract.CAPABILITIES,
            AriToolsContract.routedCapabilities(AriToolsContract.PROTOCOL_VERSION, null),
        )
    }

    @Test
    fun `a capability the host does not know is not routed`() {
        assertEquals(
            setOf(AriToolsContract.CAPABILITY_CANCEL),
            AriToolsContract.routedCapabilities(
                AriToolsContract.PROTOCOL_VERSION,
                listOf(AriToolsContract.CAPABILITY_CANCEL, "teleport"),
            ),
        )
    }

    @Test
    fun `a provider below the floor is routed nothing`() {
        assertEquals(
            emptySet<String>(),
            AriToolsContract.routedCapabilities(0, listOf(AriToolsContract.CAPABILITY_CANCEL)),
        )
    }

    @Test
    fun `every capability arrived in a protocol version this host reads`() {
        AriToolsContract.CAPABILITY_SINCE_PROTOCOL_VERSION.forEach { (name, since) ->
            assertTrue(name, since >= AriToolsContract.MIN_SUPPORTED_PROTOCOL_VERSION)
            assertTrue(name, since <= AriToolsContract.PROTOCOL_VERSION)
        }
    }

    @Test
    fun `a capability is hidden from a provider older than the version that added it`() {
        AriToolsContract.CAPABILITY_SINCE_PROTOCOL_VERSION.forEach { (name, since) ->
            assertFalse(name, name in AriToolsContract.impliedCapabilities(since - 1))
            assertTrue(name, name in AriToolsContract.impliedCapabilities(since))
        }
    }
}
