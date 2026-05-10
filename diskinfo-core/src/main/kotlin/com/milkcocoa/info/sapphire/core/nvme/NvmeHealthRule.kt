package com.milkcocoa.info.sapphire.core.nvme

import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.HealthRule
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

class NvmeHealthRule : HealthRule<MetricsSnapshot.NvmeMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.NvmeMetricsSnapshot): List<AttributeEvaluation> {
        return listOf(
            AttributeEvaluation(
                key = "nvme.percentage_used",
                value = snapshot.percentageUsed?.toLong(),
                status = when {
                    snapshot.percentageUsed == null -> AttributeStatus.UNKNOWN
                    snapshot.percentageUsed >= 100 -> AttributeStatus.BAD
                    snapshot.percentageUsed >= 80 -> AttributeStatus.CAUTION
                    else -> AttributeStatus.GOOD
                },
                threshold = 80,
                reason = "NVMe wear is approaching its rated endurance."
            )
        )
    }
}