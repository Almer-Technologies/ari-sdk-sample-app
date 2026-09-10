package com.ari_os.ari.sdk

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private const val TYPE_STRING = "string"
private const val TYPE_INT = "int"
private const val TYPE_NUMBER = "number"
private const val TYPE_BOOL = "bool"
private const val TYPE_ENUM = "enum"
private const val TYPE_INT_LIST = "int_list"
private const val TYPE_STRING_LIST = "string_list"

/**
 * One declared argument of a tool. Only [EnumArg] carries allowed values.
 *
 * A list type carries a set the tool acts on in one call, so the user confirms it once.
 * No list type may fill a deeplink uri placeholder.
 */
@Serializable(with = AriToolArgSerializer::class)
sealed interface AriToolArg {

    /** Wire name, matching [AriToolsContract.TOOL_NAME_REGEX]. */
    val name: String

    /** Whether Ari must send a value on every call. */
    val required: Boolean

    /** Text the model reads to pick a value. */
    val description: String

    /** An argument the model fills with free text. */
    data class StringArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }

    /** An argument the model fills with a whole number. */
    data class IntArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }

    /** An argument the model fills with a number. */
    data class NumberArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }

    /** An argument the model fills with true or false. */
    data class BoolArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }

    /** An argument the model fills with one of [values]. */
    data class EnumArg(
        override val name: String,
        val values: List<String>,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
            require(values.isNotEmpty()) { "arg '$name': type enum needs values" }
            require(values.size <= AriToolsContract.MAX_ENUM_VALUES) {
                "arg '$name': at most ${AriToolsContract.MAX_ENUM_VALUES} enum values, " +
                    "and this one declares ${values.size}"
            }
            require(values.none { value -> value.isBlank() }) {
                "arg '$name': enum values must not be blank"
            }
            values.forEach { value ->
                require(value.length <= AriToolsContract.MAX_ENUM_VALUE_LENGTH) {
                    "arg '$name': enum value '${value.take(MAX_ECHOED_LENGTH)}' is " +
                        "${value.length} chars, over ${AriToolsContract.MAX_ENUM_VALUE_LENGTH}"
                }
            }
            require(values.toSet().size == values.size) {
                "arg '$name': enum values must not repeat"
            }
        }
    }

    /** An argument the model fills with whole numbers. */
    data class IntListArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }

    /** An argument the model fills with free text values. */
    data class StringListArg(
        override val name: String,
        override val required: Boolean = false,
        override val description: String = "",
    ) : AriToolArg {
        init {
            requireDeclaredArg(name, description)
        }
    }
}

/**
 * The flat wire object of one argument. The cloud pins these five keys and
 * rejects any other, so the sealed hierarchy must not reach the wire as a
 * polymorphic wrapper.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private class ArgSurrogate(
    val name: String,
    val type: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val values: List<String>? = null,
    val required: Boolean = false,
    val description: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
internal object AriToolArgSerializer : KSerializer<AriToolArg> {

    override val descriptor: SerialDescriptor =
        SerialDescriptor("com.ari_os.ari.sdk.AriToolArg", ArgSurrogate.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: AriToolArg) =
        encoder.encodeSerializableValue(ArgSurrogate.serializer(), value.toSurrogate())

    override fun deserialize(decoder: Decoder): AriToolArg =
        decoder.decodeSerializableValue(ArgSurrogate.serializer()).toArg()
}

private fun AriToolArg.toSurrogate(): ArgSurrogate {
    val type = when (this) {
        is AriToolArg.StringArg -> TYPE_STRING
        is AriToolArg.IntArg -> TYPE_INT
        is AriToolArg.NumberArg -> TYPE_NUMBER
        is AriToolArg.BoolArg -> TYPE_BOOL
        is AriToolArg.EnumArg -> TYPE_ENUM
        is AriToolArg.IntListArg -> TYPE_INT_LIST
        is AriToolArg.StringListArg -> TYPE_STRING_LIST
    }
    return ArgSurrogate(
        name = name,
        type = type,
        values = (this as? AriToolArg.EnumArg)?.values,
        required = required,
        description = description,
    )
}

private fun ArgSurrogate.toArg(): AriToolArg {
    if (type != TYPE_ENUM && values != null) {
        throw SerializationException("arg '$name': only type enum takes values")
    }
    return when (type) {
        TYPE_STRING -> AriToolArg.StringArg(
            name = name,
            required = required,
            description = description,
        )

        TYPE_INT -> AriToolArg.IntArg(
            name = name,
            required = required,
            description = description,
        )

        TYPE_NUMBER -> AriToolArg.NumberArg(
            name = name,
            required = required,
            description = description,
        )

        TYPE_BOOL -> AriToolArg.BoolArg(
            name = name,
            required = required,
            description = description,
        )

        TYPE_ENUM -> AriToolArg.EnumArg(
            name = name,
            values = values.orEmpty(),
            required = required,
            description = description,
        )

        TYPE_INT_LIST -> AriToolArg.IntListArg(
            name = name,
            required = required,
            description = description,
        )

        TYPE_STRING_LIST -> AriToolArg.StringListArg(
            name = name,
            required = required,
            description = description,
        )

        else -> throw SerializationException(
            "arg '$name': unknown type '${type.take(MAX_ECHOED_LENGTH)}'"
        )
    }
}
