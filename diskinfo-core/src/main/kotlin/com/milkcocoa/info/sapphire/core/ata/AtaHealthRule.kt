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
            lifetimeRemainingRule(snapshot),
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

    private fun lifetimeRemainingRule(snapshot: MetricsSnapshot.AtaMetricsSnapshot): AttributeEvaluation {
        val lifetimeRemaining = snapshot.universal.lifetimeRemainingPercent?.toLong()
        return AttributeEvaluation(
            key = "ata.percent_lifetime_remaining",
            value = lifetimeRemaining,
            status = when {
                lifetimeRemaining == null -> AttributeStatus.UNKNOWN
                lifetimeRemaining <= 10 -> AttributeStatus.BAD
                lifetimeRemaining <= 20 -> AttributeStatus.CAUTION
                else -> AttributeStatus.GOOD
            },
            threshold = 20,
            reason = "ATA SSD lifetime remaining indicator is low.",
        )
    }
}
