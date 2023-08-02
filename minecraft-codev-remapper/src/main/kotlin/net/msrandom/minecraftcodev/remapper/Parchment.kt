package net.msrandom.minecraftcodev.remapper

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class Parchment(
    val version: String,
    val packages: List<PackageElement>? = null,
    val classes: List<ClassElement>? = null
) {
    sealed interface Element {
        val name: String

        @Serializable(Javadoc.JavadocSerializer::class)
        val javadoc: Javadoc?
    }

    data class PackageElement(override val name: String, override val javadoc: Javadoc? = null) : Element

    data class ClassElement(
        override val name: String,
        override val javadoc: Javadoc? = null,
        val fields: List<FieldElement>? = null,
        val methods: List<MethodElement>? = null
    ) : Element {
        sealed interface DescriptorElement : Element {
            val descriptor: String
        }

        data class FieldElement(
            override val name: String,
            override val javadoc: Javadoc? = null,
            override val descriptor: String
        ) : DescriptorElement

        data class MethodElement(
            override val name: String,
            override val javadoc: Javadoc? = null,
            override val descriptor: String,
            val parameters: List<ParameterElement>? = null
        ) : DescriptorElement {
            data class ParameterElement(
                val index: Int,
                override val name: String,
                override val javadoc: Javadoc? = null
            ) : Element
        }
    }

    @Serializable
    sealed interface Javadoc {
        val lines: List<String>

        @Serializer(Javadoc::class)
        object JavadocSerializer : JsonContentPolymorphicSerializer<Javadoc>(Javadoc::class) {
            override fun selectDeserializer(element: JsonElement) = if (element is JsonPrimitive) {
                object : KSerializer<JavadocLine> {
                    private val base = String.serializer()

                    override val descriptor get() = base.descriptor
                    override fun deserialize(decoder: Decoder) = JavadocLine(base.deserialize(decoder))
                    override fun serialize(encoder: Encoder, value: JavadocLine) = base.serialize(encoder, value.line)
                }
            } else {
                object : KSerializer<JavadocLines> {
                    private val base = ListSerializer(String.serializer())

                    override val descriptor get() = base.descriptor
                    override fun deserialize(decoder: Decoder) = JavadocLines(base.deserialize(decoder))
                    override fun serialize(encoder: Encoder, value: JavadocLines) = base.serialize(encoder, value.lines)
                }
            }
        }
    }

    data class JavadocLine(val line: String) : Javadoc {
        override val lines
            get() = listOf(line)
    }

    data class JavadocLines(override val lines: List<String>) : Javadoc
}
