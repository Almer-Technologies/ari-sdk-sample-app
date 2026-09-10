package com.ari_os.ari.sdk

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URISyntaxException

// Rejected text reaches the host log, so a hostile value must not flood it.
internal const val MAX_ECHOED_LENGTH = 32

internal fun requireDeclaredName(kind: String, name: String) {
    require(AriToolsContract.TOOL_NAME_REGEX.matches(name)) {
        "$kind name '${name.take(MAX_ECHOED_LENGTH)}' must match ${AriToolsContract.TOOL_NAME_REGEX.pattern}"
    }
}

internal fun requireDescriptionFits(subject: String, description: String) {
    require(description.length <= AriToolsContract.MAX_DESCRIPTION_LENGTH) {
        "$subject: description over ${AriToolsContract.MAX_DESCRIPTION_LENGTH} chars"
    }
}

internal fun requireDeclaredArg(name: String, description: String) {
    requireDeclaredName("arg", name)
    requireDescriptionFits("arg '$name'", description)
}

// Android compiles a regex with ICU, which rejects a closing brace no quantifier
// opened; the host jvm accepts one. Keep braces escaped, and keep the patterns in
// an object, so a rejected one kills only the uri check, not the whole file.
private object UriPatterns {
    val PLACEHOLDER = Regex("""\{([^{}]*)\}""")
    val LITERAL_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")
}

// java.net.URI rejects the braces of a template, so the scheme and the shape
// are checked on a copy with every placeholder replaced by this.
private const val PLACEHOLDER_STAND_IN = "x"

// The declaration bounds what a CONSTRAINED type holds. FREE_TEXT is the model's own, so a
// tool names it in AriToolsContract.FIELD_FREE_TEXT_URI_ARGS to fill a placeholder. NEVER
// has no opt in: a set has no meaning as one uri component, and the SDK will not choose a
// separator for the tool.
private enum class PlaceholderFit { CONSTRAINED, FREE_TEXT, NEVER }

private fun AriToolArg.placeholderFit(): PlaceholderFit = when (this) {
    is AriToolArg.StringArg -> PlaceholderFit.FREE_TEXT
    is AriToolArg.IntArg,
    is AriToolArg.NumberArg,
    is AriToolArg.BoolArg,
    is AriToolArg.EnumArg,
    -> PlaceholderFit.CONSTRAINED

    is AriToolArg.IntListArg,
    is AriToolArg.StringListArg,
    -> PlaceholderFit.NEVER
}

private fun listPlaceholderRefusal(tool: String, name: String) =
    "tool '$tool': arg '$name' is a list, so it cannot fill a uri placeholder"

private fun placeholderNames(uri: String): Set<String> =
    UriPatterns.PLACEHOLDER.findAll(uri).map { match -> match.groupValues[1] }.toSet()

private fun requireFreeTextUriArgs(
    tool: String,
    uri: String?,
    freeTextUriArgs: List<String>,
    args: List<AriToolArg>,
) {
    if (freeTextUriArgs.isEmpty()) return
    val key = AriToolsContract.FIELD_FREE_TEXT_URI_ARGS
    val declared = args.associateBy { arg -> arg.name }
    val filled = uri?.let(::placeholderNames).orEmpty()
    freeTextUriArgs.forEach { name ->
        val arg = declared[name]
        require(arg != null) {
            "tool '$tool': $key names '${name.take(MAX_ECHOED_LENGTH)}', which is not a declared arg"
        }
        val fit = arg.placeholderFit()
        require(fit != PlaceholderFit.NEVER) { listPlaceholderRefusal(tool, name) }
        require(fit == PlaceholderFit.FREE_TEXT) {
            "tool '$tool': $key names '$name', which is not free text"
        }
        require(name in filled) { "tool '$tool': $key names '$name', which the uri does not fill" }
    }
}

private fun requireUriTemplate(
    tool: String,
    uri: String,
    args: List<AriToolArg>,
    freeTextUriArgs: List<String>,
) {
    require(UriPatterns.LITERAL_SCHEME.containsMatchIn(uri)) {
        "tool '$tool': uri needs a literal scheme, so no arg can choose one"
    }
    try {
        URI(uri.replace(UriPatterns.PLACEHOLDER, PLACEHOLDER_STAND_IN))
    } catch (e: URISyntaxException) {
        throw IllegalArgumentException("tool '$tool': uri is not a uri template", e)
    }
    val declared = args.associateBy { arg -> arg.name }
    val filled = placeholderNames(uri)
    filled.forEach { name ->
        val arg = declared[name]
        require(arg != null) {
            "tool '$tool': uri names '${name.take(MAX_ECHOED_LENGTH)}', which is not a declared arg"
        }
        val fit = arg.placeholderFit()
        require(fit != PlaceholderFit.NEVER) { listPlaceholderRefusal(tool, name) }
        require(fit == PlaceholderFit.CONSTRAINED || name in freeTextUriArgs) {
            "tool '$tool': arg '$name' is free text, so the tool must name it in " +
                "${AriToolsContract.FIELD_FREE_TEXT_URI_ARGS} to fill a uri placeholder"
        }
    }
    args.filter { arg -> arg.required }.forEach { arg ->
        require(arg.name in filled) { "tool '$tool': uri leaves out required arg '${arg.name}'" }
    }
}

/**
 * One tool a provider app exposes to Ari.
 *
 * @property confirm Whether Ari asks the user before it runs the tool. A partner writes its
 *   own declaration, so this guards the user only if the partner sets it honestly.
 * @property presentsUi Whether the tool opens a screen and returns no data. Ari strips this
 *   key before it sends the declaration to the cloud, so it never reaches the model.
 * @property uri Deeplink template Ari opens instead of binding the provider, with one
 *   `{arg_name}` placeholder per value to fill. Ari opens it as `ACTION_VIEW` on the
 *   provider's package, and takes no action, component, extras or flags from here. Ari
 *   strips this key before it sends the declaration to the cloud, so the link stays on
 *   the device and the model only ever sees the tool's name, description and args.
 * @property freeTextUriArgs Names of args this tool lets fill a placeholder with free text.
 *   A partner writes its own declaration, so this only stops an accidental free-text
 *   deeplink and marks a deliberate one. A hostile partner can name any arg here. Ari
 *   strips this key too, for the same reason it strips [uri].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AriToolDeclaration(
    val name: String,
    val description: String,
    val confirm: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val presentsUi: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val uri: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val freeTextUriArgs: List<String> = emptyList(),
    val args: List<AriToolArg> = emptyList(),
) {
    init {
        requireDeclaredName("tool", name)
        require(description.isNotEmpty()) { "tool '$name': description is required" }
        requireDescriptionFits("tool '$name'", description)
        // Before the template check, so a wrong name reports itself, not the placeholder.
        requireFreeTextUriArgs(name, uri, freeTextUriArgs, args)
        uri?.let { template ->
            require(presentsUi) { "tool '$name': a uri tool returns no data, so presentsUi must be true" }
            requireUriTemplate(name, template, args, freeTextUriArgs)
        }
    }
}

/**
 * The declaration a provider ships at [AriToolsContract.DECLARATION_ASSET].
 *
 * @property declarationVersion Required. A file without it fails to decode, so it
 *   never passes as version 1.
 * @property protocolVersion Optional. A file without it predates the key, so it reads as
 *   [AriToolsContract.IMPLIED_PROTOCOL_VERSION].
 * @property capabilities Optional surface the provider implements, or null when the file names
 *   none. Null and an empty list differ, so read both with
 *   [AriToolsContract.routedCapabilities].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AriToolDeclarationFile(
    val declarationVersion: Int,
    @EncodeDefault
    val protocolVersion: Int = AriToolsContract.IMPLIED_PROTOCOL_VERSION,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val capabilities: List<String>? = null,
    val label: String? = null,
    val tools: List<AriToolDeclaration> = emptyList(),
)
