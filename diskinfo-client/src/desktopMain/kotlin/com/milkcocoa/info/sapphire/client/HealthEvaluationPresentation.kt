package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
internal fun NonGoodEvaluationList(snapshot: EvaluatedDiskSnapshot) {
    val evaluations = snapshot.nonGoodEvaluations()
    if (evaluations.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Policy evaluation reasons")
        evaluations.forEach { evaluation ->
            Text(
                text = "${evaluation.status.name} · ${evaluation.presentationText()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = attributeStatusColor(evaluation.status),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
