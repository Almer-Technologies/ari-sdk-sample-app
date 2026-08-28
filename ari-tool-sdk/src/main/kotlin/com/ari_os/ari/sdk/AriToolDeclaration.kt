package com.ari_os.ari.sdk

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One declared argument of a tool.
 *
 * @property type One of `string`, `int`, `number`, `bool`, `enum`.
 * @property values Allowed values. Required for `enum`, absent otherwise.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AriToolArg(
    val name: String,
    val type: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val values: List<String>? = null,
    val required: Boolean = false,
    val description: String = "",
)

/** One tool a provider app exposes to Ari. */
@Serializable
data class AriToolDeclaration(
    val name: String,
    val description: String,
    val confirm: Boolean = false,
    val args: List<AriToolArg> = emptyList(),
)

/**
 * One provider app and the tools it declares.
 *
 * [packageName] is named around Kotlin's reserved `package` keyword but
 * serializes as `"package"` — the wire key the cloud expects.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AriToolProviderDeclaration(
    @SerialName("package")
    val packageName: String,
    val label: String? = null,
    @EncodeDefault
    val declarationVersion: Int = AriToolsContract.DECLARATION_VERSION,
    val tools: List<AriToolDeclaration> = emptyList(),
)
