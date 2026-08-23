package com.milkcocoa.info.sapphire.core.nvme

import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.HealthRule
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

class NvmeHealthRule : HealthRule<MetricsSnapshot.NvmeMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.NvmeMetricsSnapshot): List<AttributeEvaluation> {
        val percentageUsed = snapshot.percentageUsed?.toLong()
        val percentageUsedStatus = when {
            percentageUsed == null -> AttributeStatus.UNKNOWN
            percentageUsed >= 100 -> AttributeStatus.BAD
            percentageUsed >= 80 -> AttributeStatus.CAUTION
            else -> AttributeStatus.GOOD
        }
        val availableSpare = snapshot.availableSpare?.toLong()
        val availableSpareStatus = when {
            availableSpare == null -> AttributeStatus.UNKNOWN
            availableSpare <= 10 -> AttributeStatus.BAD
            availableSpare <= 20 -> AttributeStatus.CAUTION
            else -> AttributeStatus.GOOD
        }
        val mediaErrors = snapshot.mediaErrors
        val mediaErrorsStatus = when {
            mediaErrors == null -> AttributeStatus.UNKNOWN
            mediaErrors > 0 -> AttributeStatus.BAD
            else -> AttributeStatus.GOOD
        }
        val criticalWarnings = snapshot.universal.criticalWarningCount?.toLong()
        val criticalWarningsStatus = when {
            criticalWarnings == null -> AttributeStatus.UNKNOWN
            criticalWarnings > 0 -> AttributeStatus.BAD
            else -> AttributeStatus.GOOD
        }

        return listOf(
            AttributeEvaluation(
                key = "nvme.percentage_used",
                value = percentageUsed,
                status = percentageUsedStatus,
                threshold = when (percentageUsedStatus) {
                    AttributeStatus.BAD -> 100
                    AttributeStatus.CAUTION, AttributeStatus.GOOD -> 80
                    AttributeStatus.UNKNOWN -> null
                },
                reason = when (percentageUsedStatus) {
                    AttributeStatus.UNKNOWN -> "NVMe percentage used is unavailable; cannot evaluate endurance thresholds."
                    AttributeStatus.BAD -> "NVMe percentage used is $percentageUsed%, at or above the BAD threshold of 100%."
                    AttributeStatus.CAUTION -> "NVMe percentage used is $percentageUsed%, at or above the CAUTION threshold of 80%."
                    AttributeStatus.GOOD -> "NVMe percentage used is $percentageUsed%, below the CAUTION threshold of 80%."
                },
            ),
            AttributeEvaluation(
                key = "nvme.available_spare",
                value = availableSpare,
                status = availableSpareStatus,
                threshold = when (availableSpareStatus) {
                    AttributeStatus.BAD -> 10
                    AttributeStatus.CAUTION, AttributeStatus.GOOD -> 20
                    AttributeStatus.UNKNOWN -> null
                },
                reason = when (availableSpareStatus) {
                    AttributeStatus.UNKNOWN -> "NVMe available spare is unavailable; cannot evaluate spare thresholds."
                    AttributeStatus.BAD -> "NVMe available spare is $availableSpare%, at or below the BAD threshold of 10%."
                    AttributeStatus.CAUTION -> "NVMe available spare is $availableSpare%, at or below the CAUTION threshold of 20%."
                    AttributeStatus.GOOD -> "NVMe available spare is $availableSpare%, above the CAUTION threshold of 20%."
                },
            ),
            AttributeEvaluation(
                key = "nvme.media_error_count",
                value = mediaErrors,
                status = mediaErrorsStatus,
                threshold = 0,
                reason = when (mediaErrorsStatus) {
                    AttributeStatus.UNKNOWN -> "NVMe media error count is unavailable; cannot verify the required value of 0."
                    AttributeStatus.BAD -> "NVMe media error count is $mediaErrors, above the required maximum of 0."
                    AttributeStatus.GOOD -> "NVMe media error count is 0, within the required normal range."
                    AttributeStatus.CAUTION -> error("Zero-value NVMe rules do not produce CAUTION.")
                },
            ),
            AttributeEvaluation(
                key = "nvme.critical_warning_count",
                value = criticalWarnings,
                status = criticalWarningsStatus,
                threshold = 0,
                reason = when (criticalWarningsStatus) {
                    AttributeStatus.UNKNOWN -> "NVMe critical warning count is unavailable; cannot verify the required value of 0."
                    AttributeStatus.BAD -> "NVMe critical warning count is $criticalWarnings, above the required maximum of 0."
                    AttributeStatus.GOOD -> "NVMe critical warning count is 0, within the required normal range."
                    AttributeStatus.CAUTION -> error("Zero-value NVMe rules do not produce CAUTION.")
                },
            ),
        )
    }
}
