package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ATA standard self-test history, including current and outdated error counts. */
@Serializable
data class AtaSmartSelfTestLog(
    val standard: Standard
) {
    /** Header and entries for the standard ATA self-test history. */
    @Serializable
    data class Standard(
        val revision: Int,
        val table: List<Entry>,
        val count: Int,
        @SerialName("error_count_total")
        val errorCountTotal: Int,
        @SerialName("error_count_outdated")
        val errorCountOutdated: Int
    )

    /** One historical self-test result, indexed by lifetime hours. */
    @Serializable
    data class Entry(
        val type: Type,
        val status: Status,
        @SerialName("lifetime_hours")
        val lifetimeHours: Int
    )

    /** Numeric and display form of a self-test type. */
    @Serializable
    data class Type(
        val value: Int,
        val string: String
    )

    /** Numeric and display form of a self-test result. */
    @Serializable
    data class Status(
        val value: Int,
        val string: String,
        val passed: Boolean
    )
}
