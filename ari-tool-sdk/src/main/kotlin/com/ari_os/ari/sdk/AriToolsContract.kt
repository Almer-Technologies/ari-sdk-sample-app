package com.ari_os.ari.sdk

/**
 * The Ari App Tools wire contract.
 *
 * Every value here is public API consumed by third-party provider apps and by
 * the Ari host. Changing one is a breaking change: bump [AIDL_VERSION] or
 * [DECLARATION_VERSION] rather than redefining an existing constant.
 */
object AriToolsContract {

    /** Intent action a provider's tool service must publish. */
    const val ACTION_TOOL_PROVIDER = "com.ari_os.ari.action.TOOL_PROVIDER"

    /** Permission a provider's service must require so only Ari can bind it. */
    const val PERMISSION_BIND_TOOL_PROVIDER = "com.ari_os.ari.permission.BIND_TOOL_PROVIDER"

    /** `<meta-data>` name pointing at the provider's declaration resource. */
    const val META_DATA_TOOLS = "com.ari_os.ari.tools"

    /** `<meta-data>` name opting a provider into runtime `listTools()`. */
    const val META_DATA_DYNAMIC = "com.ari_os.ari.tools.dynamic"

    /**
     * Version of the declaration **format** — which attributes and `type`
     * values are legal in `res/xml/ari_tools`. Readable without any IPC.
     */
    const val DECLARATION_VERSION = 1

    /**
     * Version of the **AIDL wire protocol** — method signatures and result
     * envelope. Distinct from [DECLARATION_VERSION]: a manifest-only provider
     * is never bound during discovery, so only the format version is known
     * at that point.
     */
    const val AIDL_VERSION = 1

    /**
     * Max tools a single provider may declare. Each declared tool's name and
     * description are sent to the LLM on every turn, so this caps how much
     * prompt budget one provider can claim.
     */
    const val MAX_TOOLS_PER_PROVIDER = 8

    /**
     * Max length of a tool's description string. Descriptions are spent on
     * every LLM turn alongside the tool name, so this bounds prompt size
     * per tool.
     */
    const val MAX_DESCRIPTION_LENGTH = 300

    /**
     * Validates a tool `name`: lowercase snake_case, must start with a
     * letter, max 32 chars. This is what the Ari host checks a provider's
     * declared tool names against — a name that fails this is dropped
     * silently rather than surfaced to the LLM.
     */
    val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,31}$")

    /**
     * Validates an Android package name as used in provider identity:
     * lowercase segments of letters, digits, `_`, and `.`, starting with a
     * letter, max 128 chars. This is what the Ari host checks a provider's
     * package name against when validating declarations.
     */
    val PACKAGE_NAME_REGEX = Regex("^[a-z][a-z0-9_.]{0,127}$")
}
