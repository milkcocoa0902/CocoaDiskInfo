package com.milkcocoa.info.sapphire.core.ata

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
        override val name: String = "Reallocated_Sector_Ct" // or Reallocate_NAND_Blk_Cnt
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

    @Serializable
    data object PowerCycleCount: AtaSmartAttributeId {
        override val id: Int = 12
        override val name: String = "Power_Cycle_Count"
    }

    @Serializable
    data object GSenseErrorRate: AtaSmartAttributeId {
        override val id: Int = 191
        override val name: String = "G-Sense_Error_Rate"
    }

    @Serializable
    data object PowerOffRetractCount: AtaSmartAttributeId {
        override val id: Int = 192
        override val name: String = "Power-Off_Retract_Count"
    }

    @Serializable
    data object LoadCycleCount: AtaSmartAttributeId {
        override val id: Int = 193
        override val name: String = "Load_Cycle_Count"
    }

    @Serializable
    data object TemperatureCelsius: AtaSmartAttributeId {
        override val id: Int = 194
        override val name: String = "Temperature_Celsius"
    }

    @Serializable
    data object ReallocatedEventCount: AtaSmartAttributeId {
        override val id: Int = 196
        override val name: String = "Reallocated_Event_Count"
    }

    @Serializable
    data object CurrentPendingSector: AtaSmartAttributeId {
        override val id: Int = 197
        override val name: String = "Current_Pending_Sector" // or Current_Pending_ECC_Cnt
    }

    @Serializable
    data object OfflineUncorrectable: AtaSmartAttributeId {
        override val id: Int = 198
        override val name: String = "Offline_Uncorrectable"
    }

    @Serializable
    data object UdmaCrcErrorCount: AtaSmartAttributeId {
        override val id: Int = 199
        override val name: String = "UDMA_CRC_Error_Count"
    }

    @Serializable
    data object DiskShift: AtaSmartAttributeId {
        override val id: Int = 220
        override val name: String = "Disk_Shift"
    }

    @Serializable
    data object LoadedHours: AtaSmartAttributeId {
        override val id: Int = 222
        override val name: String = "Loaded_Hours"
    }

    @Serializable
    data object LoadRetryCount: AtaSmartAttributeId {
        override val id: Int = 223
        override val name: String = "Load_Retry_Count"
    }

    @Serializable
    data object LoadFriction: AtaSmartAttributeId {
        override val id: Int = 224
        override val name: String = "Load_Friction"
    }

    @Serializable
    data object LoadInTime: AtaSmartAttributeId {
        override val id: Int = 226
        override val name: String = "Load-in_Time"
    }

    @Serializable
    data object HeadFlyingHours: AtaSmartAttributeId {
        override val id: Int = 240
        override val name: String = "Head_Flying_Hours"
    }

    @Serializable
    data object ProgramFailCount: AtaSmartAttributeId {
        override val id: Int = 171
        override val name: String = "Program_Fail_Count"
    }

    @Serializable
    data object EraseFailCount: AtaSmartAttributeId {
        override val id: Int = 172
        override val name: String = "Erase_Fail_Count"
    }

    @Serializable
    data object AveBlockEraseCount: AtaSmartAttributeId {
        override val id: Int = 173
        override val name: String = "Ave_Block-Erase_Count"
    }

    @Serializable
    data object UnexpectPowerLossCt: AtaSmartAttributeId {
        override val id: Int = 174
        override val name: String = "Unexpect_Power_Loss_Ct"
    }

    @Serializable
    data object UnusedReserveNandBlk: AtaSmartAttributeId {
        override val id: Int = 180
        override val name: String = "Unused_Reserve_NAND_Blk"
    }

    @Serializable
    data object RuntimeBadBlock: AtaSmartAttributeId {
        override val id: Int = 183
        override val name: String = "Runtime_Bad_Block" // or SATA_Interfac_Downshift
    }

    @Serializable
    data object EndToEndError: AtaSmartAttributeId {
        override val id: Int = 184
        override val name: String = "End-to-End_Error" // or Error_Correction_Count
    }

    @Serializable
    data object ReportedUncorrect: AtaSmartAttributeId {
        override val id: Int = 187
        override val name: String = "Reported_Uncorrect"
    }

    @Serializable
    data object CommandTimeout: AtaSmartAttributeId {
        override val id: Int = 188
        override val name: String = "Command_Timeout"
    }

    @Serializable
    data object HighFlyWrites: AtaSmartAttributeId {
        override val id: Int = 189
        override val name: String = "High_Fly_Writes"
    }

    @Serializable
    data object AirflowTemperatureCel: AtaSmartAttributeId {
        override val id: Int = 190
        override val name: String = "Airflow_Temperature_Cel"
    }

    @Serializable
    data object HardwareEccRecovered: AtaSmartAttributeId {
        override val id: Int = 195
        override val name: String = "Hardware_ECC_Recovered"
    }

    @Serializable
    data object PercentLifetimeRemain: AtaSmartAttributeId {
        override val id: Int = 202
        override val name: String = "Percent_Lifetime_Remain"
    }

    @Serializable
    data object WriteErrorRate: AtaSmartAttributeId {
        override val id: Int = 206
        override val name: String = "Write_Error_Rate"
    }

    @Serializable
    data object SuccessRainRecovCnt: AtaSmartAttributeId {
        override val id: Int = 210
        override val name: String = "Success_RAIN_Recov_Cnt"
    }

    @Serializable
    data object TotalLbasWritten: AtaSmartAttributeId {
        override val id: Int = 241
        override val name: String = "Total_LBAs_Written"
    }

    @Serializable
    data object TotalLbasRead: AtaSmartAttributeId {
        override val id: Int = 242
        override val name: String = "Total_LBAs_Read"
    }

    @Serializable
    data object HostProgramPageCount: AtaSmartAttributeId {
        override val id: Int = 247
        override val name: String = "Host_Program_Page_Count"
    }

    @Serializable
    data object FtlProgramPageCount: AtaSmartAttributeId {
        override val id: Int = 248
        override val name: String = "FTL_Program_Page_Count"
    }


    data class Dynamic(
        override val id: Int,
        override val name: String
    ): AtaSmartAttributeId


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
        fun of(id: Int, name: String? = null): AtaSmartAttributeId {
            val standard = when(id) {
                1 -> RawReadErrorRate
                2 -> ThroughputPerformance
                3 -> SpinUpTime
                4 -> StartStopCount
                5 -> ReallocatedSectorCt
                7 -> SeekErrorRate
                8 -> SeekTimePerformance
                9 -> PowerOnHours
                10 -> SpinRetryCount
                12 -> PowerCycleCount
                191 -> GSenseErrorRate
                192 -> PowerOffRetractCount
                193 -> LoadCycleCount
                194 -> TemperatureCelsius
                196 -> ReallocatedEventCount
                197 -> CurrentPendingSector
                198 -> OfflineUncorrectable
                199 -> UdmaCrcErrorCount
                220 -> DiskShift
                222 -> LoadedHours
                223 -> LoadRetryCount
                224 -> LoadFriction
                226 -> LoadInTime
                240 -> HeadFlyingHours
                171 -> ProgramFailCount
                172 -> EraseFailCount
                173 -> AveBlockEraseCount
                174 -> UnexpectPowerLossCt
                180 -> UnusedReserveNandBlk
                183 -> RuntimeBadBlock
                184 -> EndToEndError
                187 -> ReportedUncorrect
                188 -> CommandTimeout
                189 -> HighFlyWrites
                190 -> AirflowTemperatureCel
                195 -> HardwareEccRecovered
                202 -> PercentLifetimeRemain
                206 -> WriteErrorRate
                210 -> SuccessRainRecovCnt
                241 -> TotalLbasWritten
                242 -> TotalLbasRead
                247 -> HostProgramPageCount
                248 -> FtlProgramPageCount
                else -> null
            }

            if (standard != null) {
                if (name != null && standard.name != name) {
                    return Dynamic(id, name)
                }
                return standard
            }
            return Dynamic(id, name ?: "Unknown")
        }

        @Deprecated("Use of(id, name) instead", ReplaceWith("of(id, null)"))
        fun of(id: Int): AtaSmartAttributeId = of(id, null)
    }
}

