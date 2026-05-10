package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = AtaSmartAttributeId.Serializer::class)
sealed interface AtaSmartAttributeId {
    val id: Int
    val name: String


    @Serializable
    data object RawReadErrorRate: AtaSmartAttributeId {
        override val id: Int = 1
        override val name: String = "Raw_Read_Error_Rate"
    }

    @Serializable
    data object ThroughputPerformance: AtaSmartAttributeId {
        override val id: Int = 2
        override val name: String = "Throughput_Performance"
    }

    @Serializable
    data object SpinUpTime: AtaSmartAttributeId {
        override val id: Int = 3
        override val name: String = "Spin_Up_Time"
    }

    @Serializable
    data object StartStopCount: AtaSmartAttributeId {
        override val id: Int = 4
        override val name: String = "Start_Stop_Count"
    }

    @Serializable
    data object ReallocatedSectorCt: AtaSmartAttributeId {
        override val id: Int = 5
        override val name: String = "Reallocated_Sector_Ct"
    }

    @Serializable
    data object SeekErrorRate: AtaSmartAttributeId {
        override val id: Int = 7
        override val name: String = "Seek_Error_Rate"
    }

    @Serializable
    data object SeekTimePerformance: AtaSmartAttributeId {
        override val id: Int = 8
        override val name: String = "Seek_Time_Performance"
    }

    @Serializable
    data object PowerOnHours: AtaSmartAttributeId {
        override val id: Int = 9
        override val name: String = "Power_On_Hours"
    }

    @Serializable
    data object SpinRetryCount: AtaSmartAttributeId {
        override val id: Int = 10
        override val name: String = "Spin_Retry_Count"
    }


    data object Unknown: AtaSmartAttributeId {
        override val id: Int = -1
        override val name: String = "Unknown"
    }


    object Serializer: KSerializer<AtaSmartAttributeId>{
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
            serialName = "AtaSmartAttributeId",
            kind = PrimitiveKind.INT
        )

        override fun deserialize(decoder: Decoder): AtaSmartAttributeId {
            return AtaSmartAttributeId.of(decoder.decodeInt())
        }

        override fun serialize(encoder: Encoder, value: AtaSmartAttributeId) {
            encoder.encodeInt(value.id)
        }
    }

    companion object {
        fun of(id: Int) = when(id) {
            1 -> RawReadErrorRate
            2 -> ThroughputPerformance
            3 -> SpinUpTime
            4 -> StartStopCount
            5 -> ReallocatedSectorCt
            7 -> SeekErrorRate
            8 -> SeekTimePerformance
            9 -> PowerOnHours
            10 -> SpinRetryCount
            else -> Unknown
        }
    }
}

