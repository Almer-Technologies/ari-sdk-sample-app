package com.example.aridemo

/**
 * The `aridemo://circle/{number}` link, held in one place for both ends of it:
 * the template `AriToolService` declares and Ari fills in, and the parsing
 * [MainActivity] does on the link that arrives. The third end is the
 * `<intent-filter>`, which is XML and cannot read a Kotlin constant, so
 * `CircleDeeplinkTest` reads the manifest off disk and fails the build when its
 * scheme and host stop matching [SCHEME] and [HOST].
 *
 * Parsed with a regex rather than `android.net.Uri`, because `Uri` is stubbed to
 * return null in a JVM unit test and this app runs no Robolectric.
 */
object CircleDeeplink {

    /** Custom scheme, so nothing here is a verified https App Link. */
    const val SCHEME = "aridemo"

    const val HOST = "circle"

    /** What `show_circle` declares; Ari substitutes its `number` argument in. */
    const val TEMPLATE = "$SCHEME://$HOST/{number}"

    /**
     * Nine digits at most, so the match cannot overflow an `Int` — `Int.MAX_VALUE`
     * is ten digits. `\d` also excludes a sign, so a negative number never parses.
     */
    private val PATTERN = Regex("$SCHEME://$HOST/(\\d{1,9})")

    /**
     * The circle number [uri] asks for, or `null` when it is not this link. Not a
     * promise the circle exists: Ari's type rule bounds what arrives to a whole
     * number and stops there, as it does for every substituted value.
     */
    fun circleNumber(uri: String?): Int? =
        uri?.let { link -> PATTERN.matchEntire(link)?.groupValues?.get(1)?.toInt() }
}
