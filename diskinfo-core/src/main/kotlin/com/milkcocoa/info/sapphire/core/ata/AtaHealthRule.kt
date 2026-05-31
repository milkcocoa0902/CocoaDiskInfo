package com.milkcocoa.info.sapphire.core.ata

import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.HealthRule
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

class AtaHealthRule : HealthRule<MetricsSnapshot.AtaMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.AtaMetricsSnapshot): List<AttributeEvaluation> {
        return listOf(
            zeroRawValueRule(
                snapshot = snapshot,
                id = AtaSmartAttributeId.ReallocatedSectorCt,
                key = "ata.reallocated_sector_count",
                reason = "Reallocated sectors indicate the drive has already remapped failed sectors.",
            ),
            zeroRawValueRule(
                snapshot = snapshot,
                id = AtaSmartAttributeId.CurrentPendingSector,
                key = "ata.current_pending_sector_count",
                reason = "Pending sectors should normally be zero.",
            ),
            zeroRawValueRule(
                snapshot = snapshot,
                id = AtaSmartAttributeId.OfflineUncorrectable,
                key = "ata.offline_uncorrectable_count",
                reason = "Offline uncorrectable sectors indicate data could not be read reliably.",
            ),
            zeroRawValueRule(
                snapshot = snapshot,
                id = AtaSmartAttributeId.UdmaCrcErrorCount,
                key = "ata.udma_crc_error_count",
                reason = "UDMA CRC errors usually indicate cabling, enclosure, or link reliability problems.",
            ),
            ssdLifeRule(snapshot),
        )
    }

    private fun zeroRawValueRule(
        snapshot: MetricsSnapshot.AtaMetricsSnapshot,
        id: AtaSmartAttributeId,
        key: String,
        reason: String,
    ): AttributeEvaluation {
        val rawValue = snapshot.attributes.find { it.id == id }?.rawValue
        return AttributeEvaluation(
            key = key,
            value = rawValue,
            status = when {
                rawValue == null -> AttributeStatus.UNKNOWN
                rawValue > 0 -> AttributeStatus.BAD
                else -> AttributeStatus.GOOD
            },
            threshold = 0,
            reason = reason,
        )
    }

    private fun ssdLifeRule(snapshot: MetricsSnapshot.AtaMetricsSnapshot): AttributeEvaluation {
        val percentageUsed = snapshot.universal.percentageUsed?.toLong()
        return AttributeEvaluation(
            key = "ata.percentage_used",
            value = percentageUsed,
            status = when {
                percentageUsed == null -> AttributeStatus.UNKNOWN
                percentageUsed >= 100 -> AttributeStatus.BAD
                percentageUsed >= 80 -> AttributeStatus.CAUTION
                else -> AttributeStatus.GOOD
            },
            threshold = 80,
            reason = "ATA SSD lifetime indicator is approaching its rated endurance.",
        )
    }
}
