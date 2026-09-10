package com.ari_os.ari.sdk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
private val ASSET_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/**
 * The declaration asset, written from an [AriToolRegistry].
 *
 * Declare each tool once, in code. Write the asset from the same registry your
 * service dispatches from, commit the file, and call [requireMatches] from a unit
 * test so the two cannot drift apart.
 */
object AriToolsAsset {

    /**
     * Asset text for [registry]: fixed key order, two-space indent, trailing newline.
     *
     * @throws IllegalStateException when the text is over
     *   [AriToolsContract.MAX_DECLARATION_BYTES], which the Ari app refuses to read.
     */
    fun encode(registry: AriToolRegistry): String {
        val declaration = AriToolDeclarationFile(
            declarationVersion = AriToolsContract.DECLARATION_VERSION,
            protocolVersion = AriToolsContract.PROTOCOL_VERSION,
            // Sorted, not the map's order: requireMatches compares the asset byte for byte.
            capabilities = AriToolsContract.CAPABILITIES.sorted(),
            label = registry.label,
            tools = registry.declarations,
        )
        val text = ASSET_JSON.encodeToString(AriToolDeclarationFile.serializer(), declaration) + "\n"
        val size = text.toByteArray(Charsets.UTF_8).size
        check(size <= AriToolsContract.MAX_DECLARATION_BYTES) {
            "${AriToolsContract.DECLARATION_ASSET} is $size bytes, and Ari reads at most " +
                "${AriToolsContract.MAX_DECLARATION_BYTES} bytes. " +
                "Declare fewer tools, or write shorter descriptions."
        }
        return text
    }

    /** Writes [encode] to [AriToolsContract.DECLARATION_ASSET] inside [assetsDir]. */
    fun writeTo(assetsDir: File, registry: AriToolRegistry): File {
        val asset = assetIn(assetsDir)
        asset.parentFile?.mkdirs()
        asset.writeText(encode(registry))
        return asset
    }

    /**
     * Throws when the committed asset in [assetsDir] differs from [registry].
     *
     * A tool a partner adds in code, and forgets in the asset, then fails their
     * build instead of reaching a device.
     */
    fun requireMatches(assetsDir: File, registry: AriToolRegistry) {
        val asset = assetIn(assetsDir)
        val expected = encode(registry)
        // A checkout may store the asset with CRLF, which says nothing about drift.
        val actual = if (asset.isFile) asset.readText().replace("\r\n", "\n") else null
        if (actual == expected) return
        error(
            "${asset.path} does not match the tool registry. " +
                "Write it with AriToolsAsset.writeTo() and commit it. " +
                difference(expected, actual)
        )
    }

    private fun assetIn(assetsDir: File) = File(assetsDir, AriToolsContract.DECLARATION_ASSET)

    private fun difference(expected: String, actual: String?): String {
        if (actual == null) return "The file is missing."
        val expectedLines = expected.lines()
        val actualLines = actual.lines()
        val line = (0..maxOf(expectedLines.size, actualLines.size)).first { index ->
            expectedLines.getOrNull(index) != actualLines.getOrNull(index)
        }
        return "First difference on line ${line + 1}: the registry writes " +
            "${expectedLines.getOrNull(line).quoted()}, the file holds " +
            "${actualLines.getOrNull(line).quoted()}."
    }
}

private fun String?.quoted(): String = if (this == null) "nothing" else "'$this'"
