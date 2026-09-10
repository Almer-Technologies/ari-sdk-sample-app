package com.example.aridemo

import com.ari_os.ari.sdk.AriToolsAsset
import com.ari_os.ari.sdk.AriToolsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps `assets/ari_tools.json` and [AriToolService]'s registry from drifting apart.
 *
 * The asset is generated, never hand-written. This one test does both jobs:
 *
 *   regenerate:  ./gradlew :app:testDebugUnitTest -Pari.writeToolsAsset
 *   check only:  ./gradlew :app:testDebugUnitTest
 *
 * Without the flag it only reads, so a tool added in code and forgotten in the
 * asset fails the build here instead of quietly never reaching Ari.
 *
 * One test method on purpose: the write is a side effect on a source file, so a
 * second method asserting on that file would depend on JUnit's method order,
 * which is unspecified.
 */
class AriToolsAssetTest {

    @Test
    fun `the committed asset is at the fixed path and matches the declared tools`() {
        val registry = AriToolService().tools()

        if (WRITE_REQUESTED) {
            val written = AriToolsAsset.writeTo(ASSETS_DIR, registry)
            assertEquals(AriToolsContract.DECLARATION_ASSET, written.name)
        }

        // Names the first line that differs, or reports the file as missing.
        AriToolsAsset.requireMatches(ASSETS_DIR, registry)

        val asset = File(ASSETS_DIR, AriToolsContract.DECLARATION_ASSET)
        assertTrue("${asset.path} does not exist", asset.isFile)
    }

    private companion object {
        /** Set by the build, because a unit test's working directory is not the module. */
        val ASSETS_DIR = File(
            System.getProperty("ari.tools.assetsDir")
                ?: error("ari.tools.assetsDir is unset — run this through gradle"),
        )

        val WRITE_REQUESTED = System.getProperty("ari.tools.write") == "true"
    }
}
