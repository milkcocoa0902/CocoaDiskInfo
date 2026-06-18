package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface MetricsSnapshot: LogStructure {
    val protocol: Protocol
    val universal: UniversalMetrics

    override fun stringify(): String {
        val metrics = this

        return buildString {
            appendLine("Universal Metrics")
            appendLine("- temperature: ${universal.temperatureCelsius.formatCelsiusOrDash()}")
            appendLine("- power on hours: ${universal.powerOnHours.formatNumberOrDash()}")
            appendLine("- power cycles: ${universal.powerCycleCount.formatNumberOrDash()}")
            appendLine("- percentage used: ${universal.percentageUsed.formatPercentOrDash()}")
            appendLine("- lifetime remaining: ${universal.lifetimeRemainingPercent.formatPercentOrDash()}")
            appendLine("- total written: ${universal.totalBytesWritten.formatBytesOrDash()}")
            appendLine("- total read: ${universal.totalBytesRead.formatBytesOrDash()}")
            appendLine("- critical warnings: ${universal.criticalWarningCount ?: "-"}")
            appendLine()

            appendLine("Protocol Metrics")
            when (metrics) {
                is AtaMetricsSnapshot -> {
                    appendLine("- attributes: ${metrics.attributes.size}")
                    appendLine("- reallocated sector count: ${metrics.attributeValue(AtaSmartAttributeId.ReallocatedSectorCt)}")
                    appendLine("- current pending sector: ${metrics.attributeValue(AtaSmartAttributeId.CurrentPendingSector)}")
                    appendLine("- offline uncorrectable: ${metrics.attributeValue(AtaSmartAttributeId.OfflineUncorrectable)}")
                    appendLine("- udma crc error count: ${metrics.attributeValue(AtaSmartAttributeId.UdmaCrcErrorCount)}")
                }

                is NvmeMetricsSnapshot -> {
                    appendLine("- available spare: ${metrics.availableSpare.formatPercentOrDash()}")
                    appendLine("- percentage used: ${metrics.percentageUsed.formatPercentOrDash()}")
                    appendLine("- media errors: ${metrics.mediaErrors.formatNumberOrDash()}")
                    appendLine("- data units written: ${metrics.dataUnitsWritten.formatNumberOrDash()}")
                    appendLine("- data units read: ${metrics.dataUnitsRead.formatNumberOrDash()}")
                }
            }
        }.trimEnd()
    }

    @Serializable
    @SerialName("ata")
    data class AtaMetricsSnapshot(
        override val universal: UniversalMetrics,
        val attributes: List<AtaAttribute>
    ): MetricsSnapshot {
        override val protocol: Protocol = Protocol.ATA
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
    }
}

private fun MetricsSnapshot.AtaMetricsSnapshot.attributeValue(id: AtaSmartAttributeId): String {
    return attributes.find { it.id == id }?.value?.toString() ?: "-"
}
