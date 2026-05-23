package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MetricsSnapshot: LogStructure {
    val protocol: Protocol
    val universal: UniversalMetrics

    @Serializable
    @SerialName("ata")
    data class AtaMetricsSnapshot(
        override val universal: UniversalMetrics,
        val attributes: List<AtaAttribute>
    ): MetricsSnapshot {
        override val protocol: Protocol = Protocol.ATA

        override fun stringify(): String {
            return super.stringify()
        }
    }

    @Serializable
    @SerialName("nvme")
    data class NvmeMetricsSnapshot(
        override val universal: UniversalMetrics,
        val percentageUsed: Int?,           // 0-100+
        val availableSpare: Int?,
        val mediaErrors: Long?,
        val dataUnitsWritten: Long?,        // 512KB単位などは後で正規化
        val dataUnitsRead: Long?
    ): MetricsSnapshot {
        override val protocol: Protocol = Protocol.NVME

        override fun stringify(): String {
            return super.stringify()
        }
    }
}