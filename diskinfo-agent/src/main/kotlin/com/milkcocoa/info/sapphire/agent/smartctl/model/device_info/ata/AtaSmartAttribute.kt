package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = AtaSmartAttribute.Serializer::class)
data class AtaSmartAttribute(
    val id: AtaSmartAttributeId,
    val value: Int,
    val worst: Int,
    val thresh: Int,
    @SerialName("when_failed")
    val whenFailed: String,
    val flags: AtaSmartAttributeFlag,
    val raw: AtaSmartRawValue,
) {
    object Serializer : KSerializer<AtaSmartAttribute> {
        @Serializable
        data class Surrogate(
            val id: Int,
            val name: String,
            val value: Int,
            val worst: Int,
            val thresh: Int,
            @SerialName("when_failed")
            val whenFailed: String,
            val flags: AtaSmartAttributeFlag,
            val raw: AtaSmartRawValue,
        )

        override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

        override fun deserialize(decoder: Decoder): AtaSmartAttribute {
            val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
            return AtaSmartAttribute(
                id = AtaSmartAttributeId.of(surrogate.id, surrogate.name),
                value = surrogate.value,
                worst = surrogate.worst,
                thresh = surrogate.thresh,
                whenFailed = surrogate.whenFailed,
                flags = surrogate.flags,
                raw = surrogate.raw
            )
        }

        override fun serialize(encoder: Encoder, value: AtaSmartAttribute) {
            val surrogate = Surrogate(
                id = value.id.id,
                name = value.id.name,
                value = value.value,
                worst = value.worst,
                thresh = value.thresh,
                whenFailed = value.whenFailed,
                flags = value.flags,
                raw = value.raw
            )
            encoder.encodeSerializableValue(Surrogate.serializer(), surrogate)
        }
    }
}