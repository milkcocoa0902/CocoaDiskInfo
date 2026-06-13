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
            ),
            AttributeEvaluation(
                key = "nvme.available_spare",
                value = snapshot.availableSpare?.toLong(),
                status = when {
                    snapshot.availableSpare == null -> AttributeStatus.UNKNOWN
                    snapshot.availableSpare <= 10 -> AttributeStatus.BAD
                    snapshot.availableSpare <= 20 -> AttributeStatus.CAUTION
                    else -> AttributeStatus.GOOD
                },
                threshold = 20,
                reason = "NVMe available spare is decreasing.",
            ),
            AttributeEvaluation(
                key = "nvme.media_error_count",
                value = snapshot.mediaErrors,
                status = when {
                    snapshot.mediaErrors == null -> AttributeStatus.UNKNOWN
                    snapshot.mediaErrors > 0 -> AttributeStatus.BAD
                    else -> AttributeStatus.GOOD
                },
                threshold = 0,
                reason = "NVMe media errors should normally be zero.",
            ),
            AttributeEvaluation(
                key = "nvme.critical_warning_count",
                value = snapshot.universal.criticalWarningCount?.toLong(),
                status = when {
                    snapshot.universal.criticalWarningCount == null -> AttributeStatus.UNKNOWN
                    snapshot.universal.criticalWarningCount > 0 -> AttributeStatus.BAD
                    else -> AttributeStatus.GOOD
                },
                threshold = 0,
                reason = "NVMe critical warnings indicate the controller has reported a serious condition.",
            ),
        )
    }
}
