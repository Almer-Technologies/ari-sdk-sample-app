package com.example.aridemo

/**
 * The `aridemo://circle/{number}` link, held in one place for both ends of it.
 *
 * A deeplink tool has two halves that have to agree and normally do not live
 * together: the template `AriToolService` declares, which Ari fills in and
 * opens, and the parsing [MainActivity] does on the link that arrives. Both
 * read [TEMPLATE] and [circleNumber] here, so the tool cannot advertise a shape
 * the app does not answer — the same reason `AriToolService` takes the `color`
 * enum's values from the palette that resolves them.
 *
 * The third half is the `<intent-filter>`, which is XML and cannot read a Kotlin
 * constant. `CircleDeeplinkTest` reads the manifest off disk and fails the build
 * when its scheme and host stop matching [SCHEME] and [HOST].
 *
 * Parsed with a regex over the link's text rather than `android.net.Uri`,
 * because `Uri` is stubbed to return null in a JVM unit test and this app runs
 * no Robolectric. The rule this enforces is exactly the template's shape, so
 * there is nothing `Uri` would have told us that the pattern does not.
 */
object CircleDeeplink {

    /** Custom scheme, so nothing here is a verified https App Link. */
    const val SCHEME = "aridemo"

    /** Authority of the link. Also the noun, which keeps the template readable. */
    const val HOST = "circle"

    /**
     * What `show_circle` declares. Ari substitutes the `number` argument into
     * `{number}` and opens the result as `ACTION_VIEW` on this app's package.
     *
     * The placeholder is filled by an `int` arg, which the SDK requires: only a
     * type whose values the declaration constrains may fill a placeholder, and
     * a circle's identity in this app genuinely is a whole number.
     */
    const val TEMPLATE = "$SCHEME://$HOST/{number}"

    /**
     * Nine digits at most, so the match cannot overflow an `Int` — `Int.MAX_VALUE`
     * is ten digits. `\d` also excludes a sign, so a negative number never parses.
     */
    private val PATTERN = Regex("$SCHEME://$HOST/(\\d{1,9})")

    /**
     * The circle number [uri] asks for, or `null` when it is not this link.
     *
     * Returning a number is not a promise the circle exists. Ari's type rule
     * bounds what can arrive to a whole number; it does not check the number
     * names a circle on screen, so the caller still has to look it up. That is
     * true of every substituted value: treat it as input to validate.
     */
    fun circleNumber(uri: String?): Int? =
        uri?.let { link -> PATTERN.matchEntire(link)?.groupValues?.get(1)?.toInt() }
}
