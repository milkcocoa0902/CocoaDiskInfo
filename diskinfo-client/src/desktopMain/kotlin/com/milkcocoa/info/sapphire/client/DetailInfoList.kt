package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

@Composable
internal fun DetailInfoList(
    snapshot: EvaluatedDiskSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Information")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val metrics = snapshot.metricsSnapshot) {
                    is MetricsSnapshot.AtaMetricsSnapshot -> {
                        item {
                            AtaInformationHeader()
                        }
                        items(ataInformationRows(snapshot, metrics)) { row ->
                            AtaInformationRow(row)
                        }
                    }

                    is MetricsSnapshot.NvmeMetricsSnapshot -> {
                        item {
                            Text(
                                text = "NVMe information table is not implemented yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF9AA1A8),
                            )
                        }
                    }
                }
                if (snapshot.nonGoodEvaluations().isNotEmpty()) {
                    item { NonGoodEvaluationList(snapshot) }
                }
            }
        }
    }
}
