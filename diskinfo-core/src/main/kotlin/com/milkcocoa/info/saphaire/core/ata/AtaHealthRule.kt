package com.milkcocoa.info.saphaire.core.ata

import com.milkcocoa.info.saphaire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.saphaire.core.snapshot.AttributeStatus
import com.milkcocoa.info.saphaire.core.snapshot.HealthRule
import com.milkcocoa.info.saphaire.core.snapshot.MetricsSnapshot

class AtaHealthRule : HealthRule<MetricsSnapshot.AtaMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.AtaMetricsSnapshot): List<AttributeEvaluation> {
        return listOf(
            AttributeEvaluation(
                key = "ata.current_pending_sectors",
                value = snapshot.currentPendingSectors,
                status = when {
                    snapshot.currentPendingSectors == null -> AttributeStatus.UNKNOWN
                    snapshot.currentPendingSectors > 0 -> AttributeStatus.BAD
                    else -> AttributeStatus.GOOD
                },
                threshold = 0,
                reason = "Pending sectors should normally be zero."
            )
        )
    }
}