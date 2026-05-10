package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartSelfTestLog(
    val standard: Standard
) {
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

    @Serializable
    data class Entry(
        val type: Type,
        val status: Status,
        @SerialName("lifetime_hours")
        val lifetimeHours: Int
    )

    @Serializable
    data class Type(
        val value: Int,
        val string: String
    )

    @Serializable
    data class Status(
        val value: Int,
        val string: String,
        val passed: Boolean
    )
}
