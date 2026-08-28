package com.ari_os.ari.sdk

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException

/**
 * Parses a provider's `res/xml/ari_tools` declaration resource.
 *
 * Input is untrusted: a malformed or hostile declaration must never break a
 * session. Invalid tools are dropped individually with a reason rather than
 * failing the whole document, and a not-well-formed document itself is
 * caught and reported as a drop reason instead of throwing.
 */
object AriToolsXmlParser {

    private const val TAG_ROOT = "ari-tools"
    private const val TAG_TOOL = "tool"
    private const val TAG_ARG = "arg"

    private const val ATTR_API_VERSION = "apiVersion"
    private const val ATTR_NAME = "name"
    private const val ATTR_DESCRIPTION = "description"
    private const val ATTR_CONFIRM = "confirm"
    private const val ATTR_TYPE = "type"
    private const val ATTR_VALUES = "values"
    private const val ATTR_REQUIRED = "required"

    private val VALID_TYPES = setOf("string", "int", "number", "bool", "enum")

    /**
     * @property declarationVersion The document's `apiVersion`, or 0 when absent.
     * @property tools Tools that passed validation, capped per provider.
     * @property dropped One human-readable reason per rejected tool.
     */
    data class ParsedDeclarations(
        val declarationVersion: Int,
        val tools: List<AriToolDeclaration>,
        val dropped: List<String>,
    )

    /**
     * Read declarations from [parser].
     *
     * @param parser A parser positioned before the document start.
     * @return Valid tools plus the reasons anything was dropped. Tools parsed
     * before a not-well-formed part of the document are kept; the malformed
     * part itself is recorded as a drop reason instead of throwing.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "LongMethod")
    fun parse(parser: XmlPullParser): ParsedDeclarations {
        var declarationVersion = 0
        var sawRoot = false
        var toolOpen = false
        val tools = mutableListOf<AriToolDeclaration>()
        val dropped = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        var pendingName: String? = null
        var pendingDescription = ""
        var pendingConfirm = false
        var pendingArgs = mutableListOf<AriToolArg>()
        var pendingError: String? = null

        fun resetPending() {
            pendingName = null
            pendingDescription = ""
            pendingConfirm = false
            pendingArgs = mutableListOf()
            pendingError = null
        }

        fun flush() {
            val name = pendingName
            if (name != null) {
                val reason = pendingError ?: validateTool(name, pendingDescription)
                when {
                    reason != null -> dropped += reason
                    !seen.add(name) -> dropped += "tool '$name' dropped: duplicate name"
                    tools.size >= AriToolsContract.MAX_TOOLS_PER_PROVIDER ->
                        dropped += "tool '$name' dropped: over ${AriToolsContract.MAX_TOOLS_PER_PROVIDER}-tool cap"
                    else -> tools += AriToolDeclaration(
                        name = name,
                        description = pendingDescription,
                        confirm = pendingConfirm,
                        args = pendingArgs.toList(),
                    )
                }
            }
            resetPending()
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        TAG_ROOT -> {
                            if (!sawRoot) {
                                sawRoot = true
                                declarationVersion = parser.getAttributeValue(null, ATTR_API_VERSION)
                                    ?.toIntOrNull() ?: 0
                            }
                        }

                        TAG_TOOL -> {
                            flush()
                            toolOpen = true
                            pendingName = parser.getAttributeValue(null, ATTR_NAME).orEmpty()
                            pendingDescription = parser.getAttributeValue(null, ATTR_DESCRIPTION).orEmpty()
                            pendingConfirm = parser.getAttributeValue(null, ATTR_CONFIRM).toBoolean()
                        }

                        TAG_ARG -> {
                            val argName = parser.getAttributeValue(null, ATTR_NAME).orEmpty()
                            if (!toolOpen) {
                                dropped += "arg '$argName' dropped: found outside any <tool> element"
                            } else {
                                val type = parser.getAttributeValue(null, ATTR_TYPE).orEmpty()
                                val values = parser.getAttributeValue(null, ATTR_VALUES)
                                    ?.split(',')
                                    ?.map { it.trim() }
                                    ?.filter { it.isNotEmpty() }
                                val argError = validateArg(pendingName.orEmpty(), argName, type, values)
                                if (argError != null) {
                                    pendingError = pendingError ?: argError
                                } else {
                                    pendingArgs += AriToolArg(
                                        name = argName,
                                        type = type,
                                        values = if (type == "enum") values else null,
                                        required = parser.getAttributeValue(null, ATTR_REQUIRED).toBoolean(),
                                        description = parser.getAttributeValue(null, ATTR_DESCRIPTION).orEmpty(),
                                    )
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> if (parser.name == TAG_TOOL) {
                        flush()
                        toolOpen = false
                    }
                }
                event = parser.next()
            }
            flush()
        } catch (malformed: XmlPullParserException) {
            dropped += "declaration dropped: malformed XML (${malformed.message})"
        } catch (malformed: IOException) {
            dropped += "declaration dropped: malformed XML (${malformed.message})"
        }

        return ParsedDeclarations(declarationVersion, tools.toList(), dropped.toList())
    }

    private fun validateTool(name: String, description: String): String? = when {
        !AriToolsContract.TOOL_NAME_REGEX.matches(name) ->
            "tool '$name' dropped: name must match ${AriToolsContract.TOOL_NAME_REGEX.pattern}"

        description.isEmpty() -> "tool '$name' dropped: description is required"
        description.length > AriToolsContract.MAX_DESCRIPTION_LENGTH ->
            "tool '$name' dropped: description over ${AriToolsContract.MAX_DESCRIPTION_LENGTH} chars"

        else -> null
    }

    private fun validateArg(
        toolName: String,
        argName: String,
        type: String,
        values: List<String>?,
    ): String? = when {
        !AriToolsContract.TOOL_NAME_REGEX.matches(argName) ->
            "tool '$toolName' dropped: invalid arg name '$argName'"

        type !in VALID_TYPES -> "tool '$toolName' dropped: unknown arg type '$type'"
        type == "enum" && values.isNullOrEmpty() ->
            "tool '$toolName' dropped: enum arg '$argName' has no values"

        type != "enum" && values != null ->
            "tool '$toolName' dropped: arg '$argName' has values but is not an enum"

        else -> null
    }
}
