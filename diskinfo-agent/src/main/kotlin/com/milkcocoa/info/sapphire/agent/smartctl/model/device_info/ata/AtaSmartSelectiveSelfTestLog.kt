package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ATA selective self-test configuration and LBA ranges. */
@Serializable
data class AtaSmartSelectiveSelfTestLog(
    val revision: Int,
    val table: List<Entry>,
    val flags: Flags,
    @SerialName("power_up_scan_resume_minutes")
    val powerUpScanResumeMinutes: Int
) {
    /** One configured LBA range and its current scan status. */
    @Serializable
    data class Entry(
        @SerialName("lba_min")
        val lbaMin: Long,
        @SerialName("lba_max")
        val lbaMax: Long,
        val status: Status
    )

    /** Numeric and display form of a selective-test status. */
    @Serializable
    data class Status(
        val value: Int,
        val string: String
    )

    /** Selective-test flags, including whether the remainder scan is enabled. */
    @Serializable
    data class Flags(
        val value: Int,
        @SerialName("remainder_scan_enabled")
        val remainderScanEnabled: Boolean
    )
}
