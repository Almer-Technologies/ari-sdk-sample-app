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

private val URI_PLACEHOLDER = Regex("""\{([^{}]*)}""")

private val URI_LITERAL_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")

// java.net.URI rejects the braces of a template, so the scheme and the shape
// are checked on a copy with every placeholder replaced by this.
private const val PLACEHOLDER_STAND_IN = "x"

// Ari interpolates the value into a uri another app then handles, so only a type
// whose values the declaration constrains may fill a placeholder.
private fun AriToolArg.canFillUriPlaceholder(): Boolean = when (this) {
    is AriToolArg.StringArg -> false
    is AriToolArg.IntArg,
    is AriToolArg.NumberArg,
    is AriToolArg.BoolArg,
    is AriToolArg.EnumArg,
    -> true
}

private fun requireUriTemplate(tool: String, uri: String, args: List<AriToolArg>) {
    require(URI_LITERAL_SCHEME.containsMatchIn(uri)) {
        "tool '$tool': uri needs a literal scheme, so no arg can choose one"
    }
    try {
        URI(uri.replace(URI_PLACEHOLDER, PLACEHOLDER_STAND_IN))
    } catch (e: URISyntaxException) {
        throw IllegalArgumentException("tool '$tool': uri is not a uri template", e)
    }
    val declared = args.associateBy { arg -> arg.name }
    val filled = URI_PLACEHOLDER.findAll(uri).map { match -> match.groupValues[1] }.toSet()
    filled.forEach { name ->
        val arg = declared[name]
        require(arg != null) {
            "tool '$tool': uri names '${name.take(MAX_ECHOED_LENGTH)}', which is not a declared arg"
        }
        require(arg.canFillUriPlaceholder()) {
            "tool '$tool': arg '$name' is free text, so it cannot fill a uri placeholder"
        }
    }
    args.filter { arg -> arg.required }.forEach { arg ->
        require(arg.name in filled) { "tool '$tool': uri leaves out required arg '${arg.name}'" }
    }
}

/**
 * One tool a provider app exposes to Ari.
 *
 * @property presentsUi Whether the tool opens a screen and returns no data.
 * @property uri Deeplink template Ari opens instead of binding the provider, with one
 *   `{arg_name}` placeholder per value to fill. Ari opens it as `ACTION_VIEW` on the
 *   provider's package, and takes no action, component, extras or flags from here.
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
    val args: List<AriToolArg> = emptyList(),
) {
    init {
        requireDeclaredName("tool", name)
        require(description.isNotEmpty()) { "tool '$name': description is required" }
        requireDescriptionFits("tool '$name'", description)
        uri?.let { template ->
            require(presentsUi) { "tool '$name': a uri tool returns no data, so presentsUi must be true" }
            requireUriTemplate(name, template, args)
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
