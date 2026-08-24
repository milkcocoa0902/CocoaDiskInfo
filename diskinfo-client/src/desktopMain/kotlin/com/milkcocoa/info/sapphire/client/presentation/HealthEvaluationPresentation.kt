package com.milkcocoa.info.sapphire.client.presentation

import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot

internal fun HealthPolicyMetadata?.policyProvenanceText(): String = when (this) {
    null -> "Policy unknown (legacy server)"
    else -> "Current policy: $policyName v$policyVersion"
}

internal fun EvaluatedDiskSnapshot.healthComparisonText(): String {
    val reported = reportedHealth?.name ?: "unknown"
    return "Evaluated: ${health.name} · Device reported: $reported"
}

internal fun EvaluatedDiskSnapshot.nonGoodEvaluations(): List<AttributeEvaluation> =
    evaluations
        .filter { it.status != AttributeStatus.GOOD }
        .sortedWith(compareBy<AttributeEvaluation> { it.status.presentationRank() }.thenBy { it.ruleKey })

internal fun AttributeEvaluation.presentationText(): String = buildString {
    append(ruleKey)
    append(" · value ")
    append(value?.toString() ?: "-")
    append(" · threshold ")
    append(threshold?.toString() ?: "-")
    reason?.let {
        append(" · ")
        append(it)
    }
}

private fun AttributeStatus.presentationRank(): Int = when (this) {
    AttributeStatus.BAD -> 0
    AttributeStatus.CAUTION -> 1
    AttributeStatus.UNKNOWN -> 2
    AttributeStatus.GOOD -> 3
}
