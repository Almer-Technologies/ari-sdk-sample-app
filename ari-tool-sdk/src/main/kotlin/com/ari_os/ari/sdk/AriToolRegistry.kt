package com.ari_os.ari.sdk

/**
 * The code that runs one tool. `this` is the [AriToolCall], so a handler reads the
 * caller and the request id without taking a second parameter.
 */
typealias AriToolHandler = suspend AriToolCall.(ToolArgs) -> AriToolResult

/** One declared tool and the code that runs it. A deeplink tool runs no code. */
class AriTool internal constructor(
    val declaration: AriToolDeclaration,
    internal val handler: AriToolHandler?,
)

/** Every tool one provider app exposes. Build one with [ariTools]. */
class AriToolRegistry internal constructor(
    val label: String?,
    val tools: List<AriTool>,
) {
    init {
        require(tools.size <= AriToolsContract.MAX_TOOLS_PER_PROVIDER) {
            "a provider declares at most ${AriToolsContract.MAX_TOOLS_PER_PROVIDER} tools, " +
                "and this one declares ${tools.size}"
        }
        val repeated = tools.groupBy { tool -> tool.declaration.name }
            .filterValues { group -> group.size > 1 }
        require(repeated.isEmpty()) { "tool declared twice: ${repeated.keys.joinToString()}" }
    }

    /** What Ari reads, in declaration order. */
    val declarations: List<AriToolDeclaration> = tools.map { tool -> tool.declaration }

    internal fun find(name: String): AriTool? =
        tools.firstOrNull { tool -> tool.declaration.name == name }
}

/** Declares the arguments of one tool. */
open class AriToolArgsBuilder internal constructor() {

    internal val args = mutableListOf<AriToolArg>()

    /** An argument the model fills with free text. */
    fun string(name: String, description: String = "", required: Boolean = false) {
        args += AriToolArg.StringArg(name = name, required = required, description = description)
    }

    /** An argument the model fills with a whole number. */
    fun int(name: String, description: String = "", required: Boolean = false) {
        args += AriToolArg.IntArg(name = name, required = required, description = description)
    }

    /** An argument the model fills with a number. */
    fun number(name: String, description: String = "", required: Boolean = false) {
        args += AriToolArg.NumberArg(name = name, required = required, description = description)
    }

    /** An argument the model fills with true or false. */
    fun bool(name: String, description: String = "", required: Boolean = false) {
        args += AriToolArg.BoolArg(name = name, required = required, description = description)
    }

    /** An argument the model fills with one of [values]. */
    fun enum(
        name: String,
        values: List<String>,
        description: String = "",
        required: Boolean = false,
    ) {
        args += AriToolArg.EnumArg(
            name = name,
            values = values,
            required = required,
            description = description,
        )
    }
}

/** Declares the arguments of one deeplink tool. */
class AriDeeplinkArgsBuilder internal constructor() : AriToolArgsBuilder() {

    internal val freeTextUriArgs = mutableListOf<String>()

    /**
     * An argument the model fills with free text, and this tool lets fill a placeholder.
     *
     * Nothing in the declaration bounds the text, so your deeplink target must read the
     * value as untrusted input. Declare every other argument with [string].
     */
    fun freeTextInUri(name: String, description: String = "", required: Boolean = false) {
        string(name, description, required)
        freeTextUriArgs += name
    }
}

/** Declares one tool: its arguments, then the code that runs it. */
class AriToolBuilder internal constructor(private val name: String) : AriToolArgsBuilder() {

    internal var handler: AriToolHandler? = null
        private set

    /** The code that runs this tool. Declare it once. */
    fun handle(block: AriToolHandler) {
        require(handler == null) {
            "tool '${name.take(MAX_ECHOED_LENGTH)}': handle is declared twice"
        }
        handler = block
    }
}

/** Declares the tools of one provider app. Receiver of the [ariTools] block. */
class AriToolsBuilder internal constructor() {

    private val tools = mutableListOf<AriTool>()

    /**
     * A tool Ari runs by binding your service.
     *
     * @param confirm Whether Ari asks the user before it runs this tool. Set it on anything
     *   destructive. Nothing checks who declared it, so it guards the user only if you are honest.
     * @param presentsUi Whether the tool opens a screen and returns no data.
     */
    fun tool(
        name: String,
        description: String,
        confirm: Boolean = false,
        presentsUi: Boolean = false,
        build: AriToolBuilder.() -> Unit,
    ) {
        val builder = AriToolBuilder(name).apply(build)
        val declaration = AriToolDeclaration(
            name = name,
            description = description,
            confirm = confirm,
            presentsUi = presentsUi,
            args = builder.args.toList(),
        )
        val handler = requireNotNull(builder.handler) {
            "tool '${declaration.name}': no handler. Call handle { }, " +
                "or declare it with deeplink() if it only opens a link."
        }
        tools += AriTool(declaration, handler)
    }

    /**
     * A tool that is only a deeplink. Ari opens [uri] itself and never binds your service.
     *
     * @param uri Deeplink template, with one `{arg_name}` placeholder per value Ari fills.
     * @param confirm Whether Ari asks the user before it opens the link. Set it on anything
     *   destructive. Nothing checks who declared it, so it guards the user only if you are honest.
     */
    fun deeplink(
        name: String,
        description: String,
        uri: String,
        confirm: Boolean = false,
        build: AriDeeplinkArgsBuilder.() -> Unit = {},
    ) {
        val builder = AriDeeplinkArgsBuilder().apply(build)
        tools += AriTool(
            declaration = AriToolDeclaration(
                name = name,
                description = description,
                confirm = confirm,
                presentsUi = true,
                uri = uri,
                freeTextUriArgs = builder.freeTextUriArgs.toList(),
                args = builder.args.toList(),
            ),
            handler = null,
        )
    }

    internal fun build(label: String?): AriToolRegistry = AriToolRegistry(label, tools.toList())
}

/**
 * Declares every tool this app exposes, each with the code that runs it.
 *
 * @param label Display name Ari shows for your app. Ari reads your manifest label when null.
 */
fun ariTools(label: String? = null, build: AriToolsBuilder.() -> Unit): AriToolRegistry =
    AriToolsBuilder().apply(build).build(label)
