package com.milkcocoa.info.sapphire.core.ata

import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.HealthRule
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

class AtaHealthRule : HealthRule<MetricsSnapshot.AtaMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.AtaMetricsSnapshot): List<AttributeEvaluation> {
        val currentPendingSectors = snapshot.attributes.find { it.id == AtaSmartAttributeId.CurrentPendingSector }?.rawValue
        return listOf(
            AttributeEvaluation(
                key = "ata.current_pending_sectors",
                value = currentPendingSectors,
                status = when {
                    currentPendingSectors == null -> AttributeStatus.UNKNOWN
                    currentPendingSectors > 0 -> AttributeStatus.BAD
                    else -> AttributeStatus.GOOD
                },
                threshold = 0,
                reason = "Pending sectors should normally be zero."
            )
        )
    }
}