package com.ari_os.ari.sdk

import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AriToolsTest {

    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context> { every { contentResolver } returns resolver }

    private val registry = ariTools {
        tool("set_circle_color", "Sets the colour of the circle.") {
            handle { AriToolResult.ok() }
        }
        deeplink("open_work_order", "Opens the work order.", "hpfield://order")
    }

    private fun replyOf(accepted: Boolean, reason: String? = null) =
        mockk<Bundle>(relaxed = true) {
            every { getBoolean(AriToolsContract.KEY_ACCEPTED) } returns accepted
            every { getString(AriToolsContract.KEY_REASON) } returns reason
        }

    private fun ariAnswers(reply: Bundle?) {
        every { resolver.call(any<String>(), any(), any(), any()) } returns reply
    }

    private fun ariThrows(throwable: Throwable) {
        every { resolver.call(any<String>(), any(), any(), any()) } throws throwable
    }

    @Test
    fun `a name the registry does not declare fails at the call site`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                AriTools.setAvailable(context, setOf("set_circle_colour"), registry)
            }
        }

        assertEquals(
            "this app declares no tool named set_circle_colour. " +
                "It declares open_work_order, set_circle_color",
            failure.message,
        )
    }

    @Test
    fun `a declared deeplink can be marked available like any other tool`() = runTest {
        ariAnswers(replyOf(accepted = true))

        val result = AriTools.setAvailable(context, setOf("open_work_order"), registry)

        assertEquals(AriAvailabilityResult.Accepted, result)
    }

    @Test
    fun `a set over the tool cap fails at the call site`() {
        val tooMany = (1..AriToolsContract.MAX_TOOLS_PER_PROVIDER + 1)
            .map { index -> "tool_$index" }
            .toSet()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runTest { AriTools.setAvailable(context, tooMany) }
        }

        assertEquals(
            "at most 8 tools can be available, and this set holds 9",
            failure.message,
        )
    }

    @Test
    fun `a name of the wrong shape fails at the call site, with no registry to check it`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runTest { AriTools.setAvailable(context, setOf("Set-Circle-Color")) }
        }

        assertTrue(requireNotNull(failure.message).startsWith("tool name 'Set-Circle-Color'"))
    }

    /** A name longer than the regex allows is truncated before it reaches a log. */
    @Test
    fun `a name over the length cap fails at the call site`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            runTest { AriTools.setAvailable(context, setOf("t".repeat(40))) }
        }

        assertEquals(
            "tool name '${"t".repeat(MAX_ECHOED_LENGTH)}' must match " +
                AriToolsContract.TOOL_NAME_REGEX.pattern,
            failure.message,
        )
    }

    @Test
    fun `without a registry an undeclared name still reaches Ari`() = runTest {
        ariAnswers(replyOf(accepted = true))

        val result = AriTools.setAvailable(context, setOf("take_note"))

        assertEquals(AriAvailabilityResult.Accepted, result)
    }

    @Test
    fun `the call names the authority and the method from the contract`() = runTest {
        val authority = slot<String>()
        val method = slot<String>()
        every {
            resolver.call(capture(authority), capture(method), any(), any())
        } returns replyOf(accepted = true)

        AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriToolsContract.AUTHORITY_TOOLS, authority.captured)
        assertEquals(AriToolsContract.METHOD_SET_AVAILABLE, method.captured)
    }

    /** The whole set crosses under one key, sorted, so the write cannot depend on order. */
    @Test
    fun `the request carries the whole set under one contract key, sorted`() = runTest {
        val key = slot<String>()
        val sent = slot<ArrayList<String>>()
        mockkConstructor(Bundle::class)
        try {
            every {
                anyConstructed<Bundle>().putStringArrayList(capture(key), capture(sent))
            } just Runs
            ariAnswers(replyOf(accepted = true))

            AriTools.setAvailable(
                context,
                setOf("set_circle_color", "open_work_order"),
                registry,
            )

            assertEquals(AriToolsContract.KEY_AVAILABLE_TOOLS, key.captured)
            assertEquals(listOf("open_work_order", "set_circle_color"), sent.captured)
        } finally {
            unmockkConstructor(Bundle::class)
        }
    }

    @Test
    fun `an empty set is a valid push, so a provider can hide every tool`() = runTest {
        ariAnswers(replyOf(accepted = true))

        val result = AriTools.setAvailable(context, emptySet(), registry)

        assertEquals(AriAvailabilityResult.Accepted, result)
    }

    /** A device without Ari is the normal case for a partner app, not a failure. */
    @Test
    fun `an unresolved authority reports AriMissing instead of throwing`() = runTest {
        ariThrows(IllegalArgumentException("Unknown authority com.ari_os.ari.tools"))

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriAvailabilityResult.AriMissing, result)
    }

    @Test
    fun `a denied call reports a rejection carrying Ari's reason`() = runTest {
        ariThrows(SecurityException("caller is not allowed"))

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriAvailabilityResult.Rejected("caller is not allowed"), result)
    }

    @Test
    fun `a dead provider reports a failure a later call can retry`() = runTest {
        ariThrows(IllegalStateException("provider died"))

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriAvailabilityResult.Failed("provider died"), result)
    }

    @Test
    fun `no reply is a failure, because silence does not say the set was stored`() = runTest {
        ariAnswers(null)

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriAvailabilityResult.Failed("Ari sent no reply"), result)
    }

    @Test
    fun `a reply that refuses the set carries the reason back`() = runTest {
        ariAnswers(replyOf(accepted = false, reason = "take_note is not in your catalogue"))

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(
            AriAvailabilityResult.Rejected("take_note is not in your catalogue"),
            result,
        )
    }

    @Test
    fun `a reply with no accepted flag is a rejection, not a success`() = runTest {
        // An empty Bundle: no flag reads as false, and no reason reads as null.
        ariAnswers(mockk(relaxed = true) { every { getString(any()) } returns null })

        val result = AriTools.setAvailable(context, setOf("set_circle_color"), registry)

        assertEquals(AriAvailabilityResult.Rejected(null), result)
        assertNull((result as AriAvailabilityResult.Rejected).reason)
    }
}
