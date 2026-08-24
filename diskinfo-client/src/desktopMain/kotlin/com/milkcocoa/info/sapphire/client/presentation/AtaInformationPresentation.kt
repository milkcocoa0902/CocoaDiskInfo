package com.milkcocoa.info.sapphire.client.presentation

import com.milkcocoa.info.sapphire.core.ata.healthRuleKey
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

internal fun ataInformationRows(
    snapshot: EvaluatedDiskSnapshot,
    metrics: MetricsSnapshot.AtaMetricsSnapshot,
): List<AtaInformationRowValue> {
    val evaluations = snapshot.evaluations.associateBy { it.ruleKey }
    return metrics.attributes
        .sortedWith(compareBy<AtaAttribute> { ataStatusRank(it.evaluationStatus(evaluations)) }.thenBy { it.id.id })
        .map { attribute ->
            AtaInformationRowValue(
                status = attribute.evaluationStatus(evaluations),
                id = attribute.id.id.toString(),
                name = attribute.displayLabel(),
                value = attribute.value.toString(),
                worst = attribute.worst.toString(),
            )
        }
}

private fun ataStatusRank(status: AttributeStatus): Int = when (status) {
    AttributeStatus.BAD -> 0
    AttributeStatus.CAUTION -> 1
    AttributeStatus.UNKNOWN -> 2
    AttributeStatus.GOOD -> 3
}

private fun AtaAttribute.evaluationStatus(
    evaluations: Map<String, AttributeEvaluation>,
): AttributeStatus = evaluations[id.healthRuleKey()]?.status ?: AttributeStatus.UNKNOWN

private fun AtaAttribute.displayLabel(): String = id.name

internal data class AtaInformationRowValue(
    val status: AttributeStatus,
    val id: String,
    val name: String,
    val value: String,
    val worst: String,
)
