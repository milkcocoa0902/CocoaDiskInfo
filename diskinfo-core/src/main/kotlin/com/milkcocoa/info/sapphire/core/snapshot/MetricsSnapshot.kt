package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
/** Protocol-specific metrics plus fields shared by all supported devices. */
sealed interface MetricsSnapshot: LogStructure {
    /** Protocol discriminator used by serializers, renderers, and health rules. */
    val protocol: Protocol
    /** Values mapped to common units where possible; missing source fields remain `null`. */
    val universal: UniversalMetrics

    /** Renders a diagnostic summary, showing unavailable values as `-`. */
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
    /** ATA SMART metrics, including the parsed attribute rows. */
    data class AtaMetricsSnapshot(
        /** Cross-protocol metrics extracted from the same SMART response. */
        override val universal: UniversalMetrics,
        /** SMART rows; duplicate IDs are de-duplicated by health rules. */
        val attributes: List<AtaAttribute>
    ): MetricsSnapshot {
        /** Stable protocol discriminator for this subtype. */
        override val protocol: Protocol = Protocol.ATA
    }

    @Serializable
    @SerialName("nvme")
    /** NVMe health-log metrics; nullable values reflect missing source fields. */
    data class NvmeMetricsSnapshot(
        /** Cross-protocol metrics extracted from the same NVMe health log. */
        override val universal: UniversalMetrics,
        /** Percentage of estimated endurance consumed; devices may report values above 100. */
        val percentageUsed: Int?,           // 0-100+
        /** Remaining spare capacity percentage. */
        val availableSpare: Int?,
        /** Count of media/data-integrity errors, if reported. */
        val mediaErrors: Long?,
        /** Protocol data units written; source units are retained until normalization. */
        val dataUnitsWritten: Long?,        // 512KB単位などは後で正規化
        /** Protocol data units read; source units are retained until normalization. */
        val dataUnitsRead: Long?
    ): MetricsSnapshot {
        /** Stable protocol discriminator for this subtype. */
        override val protocol: Protocol = Protocol.NVME
    }
}

private fun MetricsSnapshot.AtaMetricsSnapshot.attributeValue(id: AtaSmartAttributeId): String {
    return attributes.find { it.id == id }?.value?.toString() ?: "-"
}
