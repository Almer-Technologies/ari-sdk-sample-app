package com.example.aridemo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * The screen end of the `show_circle` deeplink: the link Ari fires, the
 * activity it lands on, and the banner that says what arrived.
 *
 * Runs under Robolectric because the parsing alone ([CircleDeeplinkTest]) says
 * nothing about whether [MainActivity] reads the link from the intent, or picks
 * up a second one through `onNewIntent`. That second path is why the activity is
 * started through Robolectric's [ActivityController]: `ActivityScenario` cannot
 * deliver a new intent, since `onNewIntent` is protected.
 *
 * The highlight ring itself is not asserted. It is a border the screen draws,
 * with no semantics a Compose test can read, and capturing the screen as an
 * image to check its pixels does not complete under Robolectric. What is
 * asserted is the banner and that every circle is still on screen alongside the
 * highlighted one.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    /** Empty because the activity sets its own content; the rule finds that root. */
    @get:Rule
    val compose = createEmptyComposeRule()

    /** A singleton, and one Robolectric sandbox runs every test here. */
    @Before
    fun setUp() {
        CircleState.reset()
    }

    @Test
    fun `opened from the launcher, the screen shows no banner`() {
        Robolectric.buildActivity(MainActivity::class.java).setup()

        assertNoBanner()
        compose.onNodeWithText("1 of 6 circles · numbers don't change").assertExists()
        compose.onNodeWithText("Say \"Hey Ari\", then:").assertExists()
    }

    /**
     * Several circles, so the screen draws one highlighted and the rest plain,
     * and none of them goes missing because one was asked for.
     */
    @Test
    fun `a link to a circle on screen says Ari opened the app on it`() {
        CircleState.add("blue")
        CircleState.add("yellow")

        launch("aridemo://circle/2")

        compose.onNodeWithText("Ari opened this app on circle 2.").assertExists()
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    /**
     * Ari's type rule lets any whole number through, so the screen checks it and
     * says so rather than highlighting nothing in silence.
     */
    @Test
    fun `a link to a circle that is not on screen says so`() {
        launch("aridemo://circle/9")

        compose.onNodeWithText("Ari asked for circle 9, which isn't on screen.").assertExists()
    }

    /**
     * The activity is `singleTask`, so a second "show me circle N" reaches the
     * instance already on screen through `onNewIntent`, and `onCreate` never
     * reads it.
     */
    @Test
    fun `a second link replaces the first banner`() {
        val controller = launch("aridemo://circle/1")
        compose.onNodeWithText("Ari opened this app on circle 1.").assertExists()

        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("aridemo://circle/9")))

        compose.onNodeWithText("Ari asked for circle 9, which isn't on screen.").assertExists()
        compose.onNodeWithText("Ari opened this app on circle 1.").assertDoesNotExist()
    }

    @Test
    fun `a link that is not ours shows no banner`() {
        launch("aridemo://square/3")

        assertNoBanner()
    }

    /**
     * Ari can change the circles while this screen is up, so the banner reads
     * the live state rather than what was true when the link arrived.
     */
    @Test
    fun `the banner follows the circles while the screen is up`() {
        launch("aridemo://circle/2")
        compose.onNodeWithText("Ari asked for circle 2, which isn't on screen.").assertExists()

        CircleState.add("blue")
        compose.onNodeWithText("Ari opened this app on circle 2.").assertExists()

        CircleState.remove(1)
        CircleState.remove(2)
        compose.onNodeWithText("Ari asked for circle 2, which isn't on screen.").assertExists()
        compose.onNodeWithText("0 of 6 circles · numbers don't change").assertExists()
    }

    /**
     * [CircleState.add] stores any name, so the screen has to draw a circle whose
     * colour it cannot resolve rather than drop it. It falls back to grey; the
     * colour is drawing and not asserted, the circle and its number are.
     */
    @Test
    fun `a circle under a colour the palette does not know is still shown`() {
        CircleState.add("cerulean")

        Robolectric.buildActivity(MainActivity::class.java).setup()

        compose.onNodeWithText("2").assertExists()
        compose.onNodeWithText("2 of 6 circles · numbers don't change").assertExists()
    }

    /**
     * Android, not this app, decides where the link Ari fires lands. This
     * resolves the filled template against the merged manifest through
     * Robolectric's package manager — closer to a device than reading the XML,
     * but still not one.
     */
    @Test
    fun `Android routes the link Ari fires to this activity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val resolved = context.packageManager.resolveActivity(showCircle(context), 0)

        assertEquals(MainActivity::class.java.name, resolved?.activityInfo?.name)
    }

    /**
     * The filter leaves `BROWSABLE` out on purpose, so a web page firing the
     * same link finds nothing here to open.
     */
    @Test
    fun `the same link sent as browsable reaches nothing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val browsable = showCircle(context).addCategory(Intent.CATEGORY_BROWSABLE)

        assertNull(context.packageManager.resolveActivity(browsable, 0))
    }

    private fun launch(link: String): ActivityController<MainActivity> =
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_VIEW, Uri.parse(link)),
        ).setup()

    /** The intent Ari builds for `show_circle` 3: the filled template, on this package. */
    private fun showCircle(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(CircleDeeplink.TEMPLATE.replace("{number}", "3")))
            .setPackage(context.packageName)

    private fun assertNoBanner() {
        compose.onNodeWithText("Ari opened this app on", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Ari asked for circle", substring = true).assertDoesNotExist()
    }
}
