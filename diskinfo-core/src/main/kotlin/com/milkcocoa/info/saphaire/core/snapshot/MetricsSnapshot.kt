package com.milkcocoa.info.saphaire.core.snapshot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MetricsSnapshot {
    val protocol: Protocol

    @Serializable
    @SerialName("ata")
    data class AtaMetricsSnapshot(
        val reallocatedSectors: Long?,
        val currentPendingSectors: Long?,
        val offlineUncorrectable: Long?,
        val udmaCrcErrorCount: Long?
    ): MetricsSnapshot {
        override val protocol: Protocol = Protocol.ATA
    }

    @Serializable
    @SerialName("nvme")
    data class NvmeMetricsSnapshot(
        val percentageUsed: Int?,           // 0-100+
        val mediaErrors: Long?,
        val dataUnitsWritten: Long?,        // 512KB単位などは後で正規化
        val dataUnitsRead: Long?
    ): MetricsSnapshot {
        override val protocol: Protocol = Protocol.NVME
    }
}