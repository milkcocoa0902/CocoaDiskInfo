package com.milkcocoa.info.sapphire.core.ata

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = AtaSmartAttributeId.Serializer::class)
/**
 * ATA SMART identifier used by the evaluator.
 *
 * The named singleton values cover identifiers with stable semantics. [Dynamic] preserves
 * unknown/vendor-labelled IDs, and serialization intentionally stores only the numeric ID.
 */
sealed interface AtaSmartAttributeId {
    /** Numeric SMART ID emitted by smartctl and stored on the wire. */
    val id: Int
    /** Source label used for display and default rule-key generation. */
    val name: String
    /** Policy describing whether normalized or raw values should be evaluated. */
    val evaluationMode: AtaAttributeEvaluationMode
        get() = AtaAttributeEvaluationMode.NormalizedThreshold
    /** Stable key used to correlate this indicator across policy results. */
    val canonicalHealthRuleKey: String
        get() = canonicalHealthRuleKey(name)


    @Serializable
    /** SMART ID 1: raw read error rate. */
    data object RawReadErrorRate: AtaSmartAttributeId {
        override val id: Int = 1
        override val name: String = "Raw_Read_Error_Rate"
    }

    @Serializable
    /** SMART ID 2: throughput performance. */
    data object ThroughputPerformance: AtaSmartAttributeId {
        override val id: Int = 2
        override val name: String = "Throughput_Performance"
    }

    @Serializable
    /** SMART ID 3: spin-up time. */
    data object SpinUpTime: AtaSmartAttributeId {
        override val id: Int = 3
        override val name: String = "Spin_Up_Time"
    }

    @Serializable
    /** SMART ID 4: start/stop count. */
    data object StartStopCount: AtaSmartAttributeId {
        override val id: Int = 4
        override val name: String = "Start_Stop_Count"
    }

    @Serializable
    /** SMART ID 5: reallocated sectors; raw count must remain zero. */
    data object ReallocatedSectorCt: AtaSmartAttributeId {
        override val id: Int = 5
        override val name: String = "Reallocated_Sector_Ct" // or Reallocate_NAND_Blk_Cnt
        override val evaluationMode = AtaAttributeEvaluationMode.RawMaximum(maximum = 0)
        override val canonicalHealthRuleKey: String = "ata.reallocated_sector_count"
    }

    @Serializable
    /** SMART ID 7: seek error rate. */
    data object SeekErrorRate: AtaSmartAttributeId {
        override val id: Int = 7
        override val name: String = "Seek_Error_Rate"
    }

    @Serializable
    /** SMART ID 8: seek time performance. */
    data object SeekTimePerformance: AtaSmartAttributeId {
        override val id: Int = 8
        override val name: String = "Seek_Time_Performance"
    }

    @Serializable
    /** SMART ID 9: power-on hours. */
    data object PowerOnHours: AtaSmartAttributeId {
        override val id: Int = 9
        override val name: String = "Power_On_Hours"
    }

    @Serializable
    /** SMART ID 10: spin retry count. */
    data object SpinRetryCount: AtaSmartAttributeId {
        override val id: Int = 10
        override val name: String = "Spin_Retry_Count"
    }

    @Serializable
    /** SMART ID 12: power-cycle count. */
    data object PowerCycleCount: AtaSmartAttributeId {
        override val id: Int = 12
        override val name: String = "Power_Cycle_Count"
    }

    @Serializable
    /** SMART ID 191: G-sense error rate. */
    data object GSenseErrorRate: AtaSmartAttributeId {
        override val id: Int = 191
        override val name: String = "G-Sense_Error_Rate"
    }

    @Serializable
    /** SMART ID 192: power-off retract count. */
    data object PowerOffRetractCount: AtaSmartAttributeId {
        override val id: Int = 192
        override val name: String = "Power-Off_Retract_Count"
    }

    @Serializable
    /** SMART ID 193: load-cycle count. */
    data object LoadCycleCount: AtaSmartAttributeId {
        override val id: Int = 193
        override val name: String = "Load_Cycle_Count"
    }

    @Serializable
    /** SMART ID 194: temperature in the device-reported scale. */
    data object TemperatureCelsius: AtaSmartAttributeId {
        override val id: Int = 194
        override val name: String = "Temperature_Celsius"
    }

    @Serializable
    /** SMART ID 196: reallocation event count. */
    data object ReallocatedEventCount: AtaSmartAttributeId {
        override val id: Int = 196
        override val name: String = "Reallocated_Event_Count"
    }

    @Serializable
    /** SMART ID 197: pending sectors; raw count must remain zero. */
    data object CurrentPendingSector: AtaSmartAttributeId {
        override val id: Int = 197
        override val name: String = "Current_Pending_Sector" // or Current_Pending_ECC_Cnt
        override val evaluationMode = AtaAttributeEvaluationMode.RawMaximum(maximum = 0)
        override val canonicalHealthRuleKey: String = "ata.current_pending_sector_count"
    }

    @Serializable
    /** SMART ID 198: offline uncorrectable sectors; raw count must remain zero. */
    data object OfflineUncorrectable: AtaSmartAttributeId {
        override val id: Int = 198
        override val name: String = "Offline_Uncorrectable"
        override val evaluationMode = AtaAttributeEvaluationMode.RawMaximum(maximum = 0)
        override val canonicalHealthRuleKey: String = "ata.offline_uncorrectable_count"
    }

    @Serializable
    /** SMART ID 199: UDMA CRC errors; raw count must remain zero. */
    data object UdmaCrcErrorCount: AtaSmartAttributeId {
        override val id: Int = 199
        override val name: String = "UDMA_CRC_Error_Count"
        override val evaluationMode = AtaAttributeEvaluationMode.RawMaximum(maximum = 0)
        override val canonicalHealthRuleKey: String = "ata.udma_crc_error_count"
    }

    @Serializable
    /** SMART ID 220: disk shift. */
    data object DiskShift: AtaSmartAttributeId {
        override val id: Int = 220
        override val name: String = "Disk_Shift"
    }

    @Serializable
    /** SMART ID 222: loaded hours. */
    data object LoadedHours: AtaSmartAttributeId {
        override val id: Int = 222
        override val name: String = "Loaded_Hours"
    }

    @Serializable
    /** SMART ID 223: load retry count. */
    data object LoadRetryCount: AtaSmartAttributeId {
        override val id: Int = 223
        override val name: String = "Load_Retry_Count"
    }

    @Serializable
    /** SMART ID 224: load friction. */
    data object LoadFriction: AtaSmartAttributeId {
        override val id: Int = 224
        override val name: String = "Load_Friction"
    }

    @Serializable
    /** SMART ID 226: load-in time. */
    data object LoadInTime: AtaSmartAttributeId {
        override val id: Int = 226
        override val name: String = "Load-in_Time"
    }

    @Serializable
    /** SMART ID 240: head flying hours. */
    data object HeadFlyingHours: AtaSmartAttributeId {
        override val id: Int = 240
        override val name: String = "Head_Flying_Hours"
    }

    @Serializable
    /** SMART ID 171: program-fail count. */
    data object ProgramFailCount: AtaSmartAttributeId {
        override val id: Int = 171
        override val name: String = "Program_Fail_Count"
    }

    @Serializable
    /** SMART ID 172: erase-fail count. */
    data object EraseFailCount: AtaSmartAttributeId {
        override val id: Int = 172
        override val name: String = "Erase_Fail_Count"
    }

    @Serializable
    /** SMART ID 173: average block-erase count. */
    data object AveBlockEraseCount: AtaSmartAttributeId {
        override val id: Int = 173
        override val name: String = "Ave_Block-Erase_Count"
    }

    @Serializable
    /** SMART ID 174: unexpected power-loss count. */
    data object UnexpectPowerLossCt: AtaSmartAttributeId {
        override val id: Int = 174
        override val name: String = "Unexpect_Power_Loss_Ct"
    }

    @Serializable
    /** SMART ID 180: unused reserved NAND blocks. */
    data object UnusedReserveNandBlk: AtaSmartAttributeId {
        override val id: Int = 180
        override val name: String = "Unused_Reserve_NAND_Blk"
    }

    @Serializable
    /** SMART ID 183: runtime bad blocks. */
    data object RuntimeBadBlock: AtaSmartAttributeId {
        override val id: Int = 183
        override val name: String = "Runtime_Bad_Block" // or SATA_Interfac_Downshift
    }

    @Serializable
    /** SMART ID 184: end-to-end errors. */
    data object EndToEndError: AtaSmartAttributeId {
        override val id: Int = 184
        override val name: String = "End-to-End_Error" // or Error_Correction_Count
    }

    @Serializable
    /** SMART ID 187: reported uncorrectable errors. */
    data object ReportedUncorrect: AtaSmartAttributeId {
        override val id: Int = 187
        override val name: String = "Reported_Uncorrect"
    }

    @Serializable
    /** SMART ID 188: command timeout count. */
    data object CommandTimeout: AtaSmartAttributeId {
        override val id: Int = 188
        override val name: String = "Command_Timeout"
    }

    @Serializable
    /** SMART ID 189: high-fly writes. */
    data object HighFlyWrites: AtaSmartAttributeId {
        override val id: Int = 189
        override val name: String = "High_Fly_Writes"
    }

    @Serializable
    /** SMART ID 190: airflow temperature. */
    data object AirflowTemperatureCel: AtaSmartAttributeId {
        override val id: Int = 190
        override val name: String = "Airflow_Temperature_Cel"
    }

    @Serializable
    /** SMART ID 195: hardware ECC recovered count. */
    data object HardwareEccRecovered: AtaSmartAttributeId {
        override val id: Int = 195
        override val name: String = "Hardware_ECC_Recovered"
    }

    @Serializable
    /** SMART ID 202: remaining lifetime; 10% is BAD and 20% is CAUTION. */
    data object PercentLifetimeRemain: AtaSmartAttributeId {
        override val id: Int = 202
        override val name: String = "Percent_Lifetime_Remain"
        override val evaluationMode = AtaAttributeEvaluationMode.RemainingPercentage(
            badAtOrBelow = 10,
            cautionAtOrBelow = 20,
        )
        override val canonicalHealthRuleKey: String = "ata.percent_lifetime_remaining"
    }

    @Serializable
    /** SMART ID 206: write error rate. */
    data object WriteErrorRate: AtaSmartAttributeId {
        override val id: Int = 206
        override val name: String = "Write_Error_Rate"
    }

    @Serializable
    /** SMART ID 210: successful RAIN recovery count. */
    data object SuccessRainRecovCnt: AtaSmartAttributeId {
        override val id: Int = 210
        override val name: String = "Success_RAIN_Recov_Cnt"
    }

    @Serializable
    /** SMART ID 241: total LBAs written. */
    data object TotalLbasWritten: AtaSmartAttributeId {
        override val id: Int = 241
        override val name: String = "Total_LBAs_Written"
    }

    @Serializable
    /** SMART ID 242: total LBAs read. */
    data object TotalLbasRead: AtaSmartAttributeId {
        override val id: Int = 242
        override val name: String = "Total_LBAs_Read"
    }

    @Serializable
    /** SMART ID 247: host program page count. */
    data object HostProgramPageCount: AtaSmartAttributeId {
        override val id: Int = 247
        override val name: String = "Host_Program_Page_Count"
    }

    @Serializable
    /** SMART ID 248: FTL program page count. */
    data object FtlProgramPageCount: AtaSmartAttributeId {
        override val id: Int = 248
        override val name: String = "FTL_Program_Page_Count"
    }


    /** Unknown or vendor-specific ID, optionally retaining a source-provided label. */
    data class Dynamic(
        /** Numeric SMART ID, including IDs not in the standard registry. */
        override val id: Int,
        /** Label to display; unknown IDs default to `Unknown` in [of]. */
        override val name: String
    ): AtaSmartAttributeId {
        private val standard: AtaSmartAttributeId? get() = standardOf(id)
        override val evaluationMode: AtaAttributeEvaluationMode
            get() = standard?.evaluationMode ?: AtaAttributeEvaluationMode.NormalizedThreshold
        override val canonicalHealthRuleKey: String
            get() = standard?.canonicalHealthRuleKey ?: "ata.attribute_$id"
    }


    /** Serializes IDs as primitive integers so unknown IDs remain forward-compatible. */
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
        /**
         * Resolves a numeric SMART ID to a standard singleton or a [Dynamic] value.
         * A non-matching [name] deliberately yields [Dynamic] so vendor aliases are not
         * silently presented as the standard interpretation.
         */
        fun of(id: Int, name: String? = null): AtaSmartAttributeId {
            val standard = standardOf(id)

            if (standard != null) {
                if (name != null && standard.name != name) {
                    return Dynamic(id, name)
                }
                return standard
            }
            return Dynamic(id, name ?: "Unknown")
        }

        private fun standardOf(id: Int): AtaSmartAttributeId? = when (id) {
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

        @Deprecated("Use of(id, name) instead", ReplaceWith("of(id, null)"))
        /** Compatibility overload for callers that do not have a source label. */
        fun of(id: Int): AtaSmartAttributeId = of(id, null)
    }
}

/** Returns the canonical health-rule key retained for compatibility with older callers. */
fun AtaSmartAttributeId.healthRuleKey(): String = canonicalHealthRuleKey

private fun canonicalHealthRuleKey(name: String): String {
    return "ata.${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"
}
