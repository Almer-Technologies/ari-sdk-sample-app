package com.example.aridemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The child elements of [this] with the given tag. `getElementsByTagName` searches deeply. */
private fun Element.elements(tag: String): List<Element> =
    (0 until childNodes.length)
        .map { index -> childNodes.item(index) }
        .filterIsInstance<Element>()
        .filter { child -> child.tagName == tag }

/**
 * The `show_circle` deeplink, checked across all three places it is written.
 *
 * A deeplink is the one kind of tool Ari runs without this app's code, so nothing
 * at runtime reports it broken: if the `<intent-filter>` does not match, Android
 * drops the intent and the user sees nothing happen — no error, no log. So the
 * agreement between the declared template, the parser and the manifest is checked
 * here, at build time, or not at all.
 *
 * The manifest is parsed off disk with the JDK's XML reader, so this proves the
 * filter is **declared** to match. It does not prove Android resolves it, which
 * needs a device; see "Not verified on hardware".
 */
class CircleDeeplinkTest {

    private val declaration = AriToolService().tools().declarations
        .single { tool -> tool.name == "show_circle" }

    @Test
    fun `the declared template is the one constant both ends read`() {
        assertEquals(CircleDeeplink.TEMPLATE, declaration.uri)
        assertEquals("aridemo://circle/{number}", declaration.uri)
    }

    /** What Ari produces after substituting, which is what the app then parses. */
    @Test
    fun `a filled template parses back to the number Ari put in it`() {
        val filled = CircleDeeplink.TEMPLATE.replace("{number}", "7")

        assertEquals(7, CircleDeeplink.circleNumber(filled))
    }

    @Test
    fun `a link for another scheme or host is not ours`() {
        assertNull(CircleDeeplink.circleNumber("other://circle/3"))
        assertNull(CircleDeeplink.circleNumber("aridemo://square/3"))
        assertNull(CircleDeeplink.circleNumber("aridemo://circle/3/extra"))
    }

    /**
     * Ari's type rule already stops the model writing free text into the
     * placeholder; this holds when something else sends the link.
     */
    @Test
    fun `a link whose number is not a number is refused`() {
        assertNull(CircleDeeplink.circleNumber("aridemo://circle/three"))
        assertNull(CircleDeeplink.circleNumber("aridemo://circle/-1"))
        assertNull(CircleDeeplink.circleNumber("aridemo://circle/"))
        assertNull(CircleDeeplink.circleNumber(null))
    }

    /** Ten digits would overflow an `Int`, so the pattern stops at nine. */
    @Test
    fun `a number too long to be an int is refused rather than wrapped`() {
        assertEquals(999_999_999, CircleDeeplink.circleNumber("aridemo://circle/999999999"))
        assertNull(CircleDeeplink.circleNumber("aridemo://circle/9999999999"))
    }

    @Test
    fun `the manifest filter accepts the scheme and host the template declares`() {
        val data = viewFilterData()

        assertEquals(CircleDeeplink.SCHEME, data.getAttribute(SCHEME_ATTR))
        assertEquals(CircleDeeplink.HOST, data.getAttribute(HOST_ATTR))
        assertTrue(
            "the template does not start with what the filter matches",
            CircleDeeplink.TEMPLATE.startsWith(
                "${CircleDeeplink.SCHEME}://${CircleDeeplink.HOST}/",
            ),
        )
    }

    /**
     * `startActivity` adds `CATEGORY_DEFAULT`, so a filter without it never
     * matches the intent Ari sends.
     */
    @Test
    fun `the filter declares the default category an implicit intent needs`() {
        assertTrue(
            "no DEFAULT category on the VIEW filter",
            "android.intent.category.DEFAULT" in viewFilterCategories(),
        )
    }

    /**
     * Deliberately absent, and pinned so it is not added by habit. Ari sets this
     * app's package on the intent, so `BROWSABLE` buys nothing and would let any
     * web page fire `aridemo://circle/3` at this app.
     */
    @Test
    fun `the filter is not browsable`() {
        assertTrue(
            "BROWSABLE opens this link to any web page",
            "android.intent.category.BROWSABLE" !in viewFilterCategories(),
        )
    }

    /** Ari is another app, so an unexported activity is one it cannot open. */
    @Test
    fun `the activity holding the filter is exported`() {
        assertEquals("true", mainActivity().getAttribute("android:exported"))
    }

    private fun mainActivity(): Element =
        MANIFEST.elements("activity").single { activity ->
            activity.getAttribute("android:name") == ".MainActivity"
        }

    private fun viewFilter(): Element =
        mainActivity().elements("intent-filter").single { filter ->
            filter.elements("action").any { action ->
                action.getAttribute("android:name") == "android.intent.action.VIEW"
            }
        }

    private fun viewFilterData(): Element = viewFilter().elements("data").single()

    private fun viewFilterCategories(): List<String> =
        viewFilter().elements("category").map { it.getAttribute("android:name") }

    private companion object {
        const val SCHEME_ATTR = "android:scheme"
        const val HOST_ATTR = "android:host"

        /**
         * Namespace-unaware on purpose, so an attribute is read as the literal
         * `android:scheme` the file is written with.
         */
        val MANIFEST: Element = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(
                File(
                    System.getProperty("ari.app.manifest")
                        ?: error("ari.app.manifest is unset — run this through gradle"),
                ),
            )
            .documentElement
            .elements("application")
            .single()
    }
}
