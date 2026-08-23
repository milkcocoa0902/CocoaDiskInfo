package com.milkcocoa.info.sapphire.core.ata

import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.HealthRule
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

class AtaHealthRule : HealthRule<MetricsSnapshot.AtaMetricsSnapshot> {
    override fun evaluate(snapshot: MetricsSnapshot.AtaMetricsSnapshot): List<AttributeEvaluation> {
        val evaluations = snapshot.attributes
            // smartctl normally emits one row per ID. Keep the first row if a
            // malformed input repeats one, so each observed ID has one result.
            .distinctBy { it.id.id }
            .sortedBy { it.id.id }
            .map(::evaluateAttribute)

        check(evaluations.map { it.ruleKey }.distinct().size == evaluations.size) {
            "ATA health rule keys must be unique within one snapshot."
        }
        return evaluations
    }

    private fun evaluateAttribute(attribute: AtaAttribute): AttributeEvaluation {
        return when (val mode = attribute.id.evaluationMode) {
            AtaAttributeEvaluationMode.NormalizedThreshold -> normalizedThresholdRule(attribute)
            is AtaAttributeEvaluationMode.RawMaximum -> rawMaximumRule(attribute, mode)
            is AtaAttributeEvaluationMode.RemainingPercentage -> remainingPercentageRule(attribute, mode)
        }
    }

    private fun rawMaximumRule(
        attribute: AtaAttribute,
        mode: AtaAttributeEvaluationMode.RawMaximum,
    ): AttributeEvaluation {
        val rawValue = attribute.rawValue
        val status = if (rawValue > mode.maximum) AttributeStatus.BAD else AttributeStatus.GOOD
        val metricName = attribute.displayName()
        return AttributeEvaluation(
            key = attribute.id.canonicalHealthRuleKey,
            value = rawValue,
            status = status,
            threshold = mode.maximum,
            reason = if (status == AttributeStatus.BAD) {
                "$metricName is $rawValue, above the required maximum of ${mode.maximum}."
            } else {
                "$metricName is $rawValue, within the required maximum of ${mode.maximum}."
            },
        )
    }

    private fun remainingPercentageRule(
        attribute: AtaAttribute,
        mode: AtaAttributeEvaluationMode.RemainingPercentage,
    ): AttributeEvaluation {
        val remaining = attribute.value.toLong()
        val status = when {
            remaining <= mode.badAtOrBelow -> AttributeStatus.BAD
            remaining <= mode.cautionAtOrBelow -> AttributeStatus.CAUTION
            else -> AttributeStatus.GOOD
        }
        return AttributeEvaluation(
            key = attribute.id.canonicalHealthRuleKey,
            value = remaining,
            status = status,
            threshold = if (status == AttributeStatus.BAD) mode.badAtOrBelow else mode.cautionAtOrBelow,
            reason = when (status) {
                AttributeStatus.BAD -> "ATA SSD lifetime remaining is $remaining%, at or below the BAD threshold of ${mode.badAtOrBelow}%."
                AttributeStatus.CAUTION -> "ATA SSD lifetime remaining is $remaining%, at or below the CAUTION threshold of ${mode.cautionAtOrBelow}%."
                AttributeStatus.GOOD -> "ATA SSD lifetime remaining is $remaining%, above the CAUTION threshold of ${mode.cautionAtOrBelow}%."
                AttributeStatus.UNKNOWN -> error("Remaining-percentage ATA rules always have an observed value.")
            },
        )
    }

    private fun normalizedThresholdRule(attribute: AtaAttribute): AttributeEvaluation {
        val threshold = attribute.threshold.toLong()
        val value = attribute.value.toLong()
        val worst = attribute.worst.toLong()
        val metricName = attribute.displayName()
        val status = when {
            threshold <= 0 -> AttributeStatus.UNKNOWN
            value <= threshold -> AttributeStatus.BAD
            worst <= threshold -> AttributeStatus.CAUTION
            else -> AttributeStatus.GOOD
        }
        return AttributeEvaluation(
            key = attribute.id.canonicalHealthRuleKey,
            value = value,
            status = status,
            threshold = threshold.takeIf { it > 0 },
            reason = when (status) {
                AttributeStatus.UNKNOWN -> "$metricName reports a non-actionable SMART threshold of ${attribute.threshold}; cannot evaluate it safely."
                AttributeStatus.BAD -> "$metricName normalized value is $value, at or below the device-reported failure threshold of $threshold."
                AttributeStatus.CAUTION -> "$metricName normalized value is $value, but its recorded worst value is $worst, at or below the device-reported failure threshold of $threshold."
                AttributeStatus.GOOD -> "$metricName normalized value is $value and worst value is $worst, both above the device-reported failure threshold of $threshold."
            },
        )
    }

    private fun AtaAttribute.displayName(): String = name.ifBlank { id.name }
}
