package com.ari_os.ari.sdk

/** The Ari App Tools wire contract, shared by provider apps and the Ari host. */
object AriToolsContract {

    /** Intent action a provider's tool service must publish. */
    const val ACTION_TOOL_PROVIDER = "com.ari_os.ari.action.TOOL_PROVIDER"

    /** Permission a provider's service must require so only Ari can bind it. */
    const val PERMISSION_BIND_TOOL_PROVIDER = "com.ari_os.ari.permission.BIND_TOOL_PROVIDER"

    /** Asset holding the provider's tool declaration, decoded by `AriToolDeclarationFile`. */
    const val DECLARATION_ASSET = "ari_tools.json"

    /**
     * `<application>` meta-data name a provider sets to `true`, so Ari finds it with no service.
     * Same text as [AUTHORITY_TOOLS], and a different thing: that one is Ari's own provider.
     */
    const val META_DATA_TOOL_PROVIDER = "com.ari_os.ari.tools"

    /** Authority of the Ari provider a partner pushes its available tools to. */
    const val AUTHORITY_TOOLS = "com.ari_os.ari.tools"

    /** `ContentProvider.call` method replacing the caller's whole available set. */
    const val METHOD_SET_AVAILABLE = "set_available"

    /** Request key of [METHOD_SET_AVAILABLE], holding tool names as a string `ArrayList`. */
    const val KEY_AVAILABLE_TOOLS = "available_tools"

    /** Reply key of [METHOD_SET_AVAILABLE], holding whether Ari stored the set. */
    const val KEY_ACCEPTED = "accepted"

    /** Reply key of [METHOD_SET_AVAILABLE], holding why Ari refused the set. */
    const val KEY_REASON = "reason"

    /** Version of the declaration format in [DECLARATION_ASSET]. */
    const val DECLARATION_VERSION = 2

    /** Wire key carrying [DECLARATION_VERSION] in [DECLARATION_ASSET]. */
    const val FIELD_DECLARATION_VERSION = "declarationVersion"

    /** Declaration format versions the host reads: its own or older. */
    val SUPPORTED_DECLARATION_VERSIONS = 1..DECLARATION_VERSION

    /** Version of the AIDL surface the SDK in this artifact implements. */
    const val PROTOCOL_VERSION = 1

    /** Lowest protocol version the host talks to. */
    const val MIN_SUPPORTED_PROTOCOL_VERSION = 1

    /**
     * Protocol version of a declaration that names none.
     *
     * Frozen at 1, because it describes files written before [FIELD_PROTOCOL_VERSION] existed.
     * It must never track [PROTOCOL_VERSION]: that would read an old file as the newest surface.
     */
    const val IMPLIED_PROTOCOL_VERSION = 1

    /** Wire key carrying the provider's protocol version in [DECLARATION_ASSET]. */
    const val FIELD_PROTOCOL_VERSION = "protocolVersion"

    /** Wire key carrying the provider's capability names in [DECLARATION_ASSET]. */
    const val FIELD_CAPABILITIES = "capabilities"

    /** Wire key naming the args of one tool that may fill a uri placeholder with free text. */
    const val FIELD_FREE_TEXT_URI_ARGS = "freeTextUriArgs"

    /** Capability of a provider that implements `IAriToolProvider.cancel`. */
    const val CAPABILITY_CANCEL = "cancel"

    /** Capability of a provider that returns a [RESULT_KIND_LAUNCH] result. */
    const val CAPABILITY_LAUNCH_RESULT = "launch_result"

    /**
     * Every capability, and the protocol version that added it.
     *
     * `invoke` is absent on purpose. A provider that cannot be invoked is not a provider, so
     * [MIN_SUPPORTED_PROTOCOL_VERSION] covers that surface instead.
     */
    val CAPABILITY_SINCE_PROTOCOL_VERSION: Map<String, Int> = mapOf(
        CAPABILITY_CANCEL to 1,
        CAPABILITY_LAUNCH_RESULT to 1,
    )

    /** Every capability name the host can route. */
    val CAPABILITIES: Set<String> = CAPABILITY_SINCE_PROTOCOL_VERSION.keys

    /**
     * Whether the host talks to a provider that declares [protocolVersion].
     *
     * A floor, not a range. The AIDL surface only grows, and an incompatible change means a
     * second interface. So a provider newer than the host implements more than the host calls.
     */
    fun speaksSupportedProtocol(protocolVersion: Int): Boolean =
        protocolVersion >= MIN_SUPPORTED_PROTOCOL_VERSION

    /** Capabilities every provider on [protocolVersion] implements, capped by what the host knows. */
    fun impliedCapabilities(protocolVersion: Int): Set<String> {
        if (!speaksSupportedProtocol(protocolVersion)) return emptySet()
        return CAPABILITY_SINCE_PROTOCOL_VERSION
            .filterValues { since -> since <= protocolVersion }
            .keys
    }

    /**
     * Capabilities the host routes to a provider.
     *
     * @param declaredCapabilities Names from [FIELD_CAPABILITIES], or null when the declaration
     *   names none. Null is not an empty list. An empty list says the provider implements nothing
     *   optional. Null says the file predates the key, so [impliedCapabilities] answers for it.
     */
    fun routedCapabilities(
        protocolVersion: Int,
        declaredCapabilities: List<String>?,
    ): Set<String> {
        val implied = impliedCapabilities(protocolVersion)
        if (declaredCapabilities == null) return implied
        return declaredCapabilities.toSet() intersect implied
    }

    /** Result envelope key naming which kind of result the envelope holds. */
    const val FIELD_RESULT_KIND = "kind"

    /** [FIELD_RESULT_KIND] of a successful result. */
    const val RESULT_KIND_OK = "ok"

    /** [FIELD_RESULT_KIND] of a failed result. */
    const val RESULT_KIND_FAILURE = "failure"

    /**
     * [FIELD_RESULT_KIND] of a result that opens a screen. The envelope carries no
     * data, and the `PendingIntent` crosses Binder next to it.
     */
    const val RESULT_KIND_LAUNCH = "launch"

    /** Error envelope `code` for arguments that do not fit the tool. */
    const val ERROR_CODE_INVALID_ARGUMENT = "invalid_argument"

    /** Error envelope `code` for a tool name the provider app does not declare. */
    const val ERROR_CODE_UNKNOWN_TOOL = "unknown_tool"

    /** Error envelope `code` for a tool the provider app cannot run right now. */
    const val ERROR_CODE_UNAVAILABLE = "unavailable"

    /** Error envelope `code` for a tool the user or a policy refused. */
    const val ERROR_CODE_DENIED = "denied"

    /** Error envelope `code` for an invocation that stopped before it produced a result. */
    const val ERROR_CODE_CANCELLED = "cancelled"

    /** Error envelope `code` for a provider app that failed while it ran the tool. */
    const val ERROR_CODE_APP_ERROR = "app_error"

    /**
     * Max size of one result envelope, in UTF-8 bytes. A larger result reports
     * [ERROR_CODE_APP_ERROR] instead of crossing Binder.
     *
     * Binder shares about 1 MB of transaction buffer per process, and a String
     * crosses it as UTF-16, so eight results of this size fit at once.
     */
    const val MAX_RESULT_BYTES = 64 * 1024

    /**
     * Max size of one result envelope the Ari cloud keeps, in UTF-8 bytes. The smaller of the
     * two result caps, and the one that binds, so a provider builds against this number.
     *
     * The Ari cloud discards a larger result and tells the model the app returned too much
     * data. So this is context economics, not transport: the cloud re-sends a tool result to
     * the model on every later turn of the session.
     *
     * The cloud measures the data the tool returned, and the SDK measures the whole envelope,
     * so the SDK refuses a result about thirty bytes earlier.
     *
     * The Ari cloud's app tools service mirrors this number. Both must hold the same value, or
     * a result passes one layer and is dropped by another. It is also the number
     * [MAX_ARGS_BYTES] holds, in the other direction.
     */
    const val MAX_CLOUD_RESULT_BYTES = 8 * 1024

    /**
     * Max size of one arguments object, in UTF-8 bytes. Larger arguments report
     * [ERROR_CODE_INVALID_ARGUMENT] and never reach the tool.
     */
    const val MAX_ARGS_BYTES = 8 * 1024

    /**
     * Max size of the generated [DECLARATION_ASSET], in UTF-8 bytes. A larger asset fails the
     * provider's own build.
     *
     * The Ari app's declaration reader mirrors this number. Both must hold the same value, or
     * a declaration passes the build and is refused on the device.
     *
     * Nothing caps how many tools one provider declares, so this is the only bound on the size
     * of its catalogue.
     */
    const val MAX_DECLARATION_BYTES = 64 * 1024

    /** Max length of a tool's description string. */
    const val MAX_DESCRIPTION_LENGTH = 300

    /**
     * Max values one enum argument may declare.
     *
     * The Ari app and the Ari cloud mirror this number. All three must hold the same value,
     * or a declaration passes one layer and is refused by another.
     */
    const val MAX_ENUM_VALUES = 32

    /**
     * Max length of one enum value, in chars.
     *
     * The Ari app and the Ari cloud mirror this number. All three must hold the same value,
     * or a declaration passes one layer and is refused by another.
     */
    const val MAX_ENUM_VALUE_LENGTH = 64

    /**
     * Max elements one list argument may carry. A longer list reports
     * [ERROR_CODE_INVALID_ARGUMENT] and never reaches the tool.
     *
     * [MAX_ARGS_BYTES] already bounds the transaction, so this bounds something else:
     * how many actions one confirmation authorizes. A list argument exists so the user
     * says yes once for a set, which makes the size of that set the thing to cap.
     *
     * It also clears [MAX_ENUM_VALUES], so a list over a whole declared value set
     * still fits.
     */
    const val MAX_LIST_ELEMENTS = 32

    /** The shape every tool `name` and arg `name` must have: lowercase snake_case. */
    val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")
}
