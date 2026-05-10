package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartSelectiveSelfTestLog(
    val revision: Int,
    val table: List<Entry>,
    val flags: Flags,
    @SerialName("power_up_scan_resume_minutes")
    val powerUpScanResumeMinutes: Int
) {
    @Serializable
    data class Entry(
        @SerialName("lba_min")
        val lbaMin: Long,
        @SerialName("lba_max")
        val lbaMax: Long,
        val status: Status
    )

    @Serializable
    data class Status(
        val value: Int,
        val string: String
    )

    @Serializable
    data class Flags(
        val value: Int,
        @SerialName("remainder_scan_enabled")
        val remainderScanEnabled: Boolean
    )
}
