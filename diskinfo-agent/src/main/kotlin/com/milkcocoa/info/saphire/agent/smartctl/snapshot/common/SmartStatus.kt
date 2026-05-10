package com.milkcocoa.info.saphire.agent.smartctl.snapshot.common

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = SmartStatus.SmartStatusSerializer::class)
@SerialName("smart_status")
sealed interface SmartStatus {
    val passed: Boolean

    @Serializable
    @SerialName("ata_smart_status")
    data class AtaSmartStatus(
        @SerialName("passed")
        override val passed: Boolean
    ): SmartStatus


    @Serializable
    @SerialName("nvme_smart_status")
    data class NvmeSmartStatus(
        @SerialName("passed")
        override val passed: Boolean,
        @SerialName("nvme")
        val nvme: Nvme
    ): SmartStatus {
        @Serializable
        @SerialName("nvme")
        data class Nvme(
            @SerialName("value")
            val value: Int
        )
    }


    object SmartStatusSerializer: JsonContentPolymorphicSerializer<SmartStatus>(SmartStatus::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SmartStatus> {
            return when{
                element.jsonObject.contains("nvme") -> NvmeSmartStatus.serializer()
                else -> AtaSmartStatus.serializer()
            }
        }
    }
}