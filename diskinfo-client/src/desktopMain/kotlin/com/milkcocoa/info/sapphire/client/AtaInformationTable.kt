package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

@Composable
internal fun AtaInformationHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Status",
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFFA7ADB3),
        )
        Text(
            text = "ID",
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFFA7ADB3),
        )
        Text(
            text = "Item",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFFA7ADB3),
        )
        Text(
            text = "Value",
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFFA7ADB3),
        )
        Text(
            text = "Worst",
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelMedium,
            color = androidx.compose.ui.graphics.Color(0xFFA7ADB3),
        )
    }
}

@Composable
internal fun AtaInformationRow(row: AtaInformationRowValue) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EvaluationBadge(row.status)
        Text(
            text = row.id,
            modifier = Modifier.width(52.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFE7E9EB),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFE7E9EB),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.value,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFE7E9EB),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = row.worst,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = androidx.compose.ui.graphics.Color(0xFFE7E9EB),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EvaluationBadge(status: AttributeStatus) {
    Surface(
        modifier = Modifier.width(72.dp),
        shape = RoundedCornerShape(8.dp),
        color = attributeStatusColor(status).copy(alpha = 0.16f),
        contentColor = attributeStatusColor(status),
    ) {
        Text(
            text = status.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun ataInformationRows(
    snapshot: DiskSnapshot,
    metrics: MetricsSnapshot.AtaMetricsSnapshot,
): List<AtaInformationRowValue> {
    val evaluations = snapshot.evaluations.associateBy { it.key }
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

private fun ataStatusRank(status: AttributeStatus): Int {
    return when (status) {
        AttributeStatus.BAD -> 0
        AttributeStatus.CAUTION -> 1
        AttributeStatus.UNKNOWN -> 2
        AttributeStatus.GOOD -> 3
    }
}

private fun AtaAttribute.evaluationStatus(evaluations: Map<String, AttributeEvaluation>): AttributeStatus {
    val explicitStatus = evaluationKey()?.let { evaluations[it]?.status }
    return explicitStatus ?: smartCurrentThresholdStatus()
}

private fun AtaAttribute.evaluationKey(): String? {
    return when (id) {
        AtaSmartAttributeId.ReallocatedSectorCt -> "ata.reallocated_sector_count"
        AtaSmartAttributeId.CurrentPendingSector -> "ata.current_pending_sector_count"
        AtaSmartAttributeId.OfflineUncorrectable -> "ata.offline_uncorrectable_count"
        AtaSmartAttributeId.UdmaCrcErrorCount -> "ata.udma_crc_error_count"
        AtaSmartAttributeId.PercentLifetimeRemain -> "ata.percent_lifetime_remaining"
        else -> null
    }
}

private fun AtaAttribute.smartCurrentThresholdStatus(): AttributeStatus {
    return when {
        threshold > 0 && value <= threshold -> AttributeStatus.BAD
        else -> AttributeStatus.GOOD
    }
}

private fun AtaAttribute.displayLabel(): String {
    return id.name
}

internal data class AtaInformationRowValue(
    val status: AttributeStatus,
    val id: String,
    val name: String,
    val value: String,
    val worst: String,
)
