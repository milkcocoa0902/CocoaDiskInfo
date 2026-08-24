package com.milkcocoa.info.sapphire.client.ui

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
import com.milkcocoa.info.sapphire.client.presentation.attributeStatusColor
import com.milkcocoa.info.sapphire.client.presentation.nonGoodEvaluations
import com.milkcocoa.info.sapphire.client.presentation.presentationText
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot

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
