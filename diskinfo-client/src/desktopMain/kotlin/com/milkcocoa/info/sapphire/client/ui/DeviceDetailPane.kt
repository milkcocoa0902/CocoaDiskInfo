package com.milkcocoa.info.sapphire.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.client.presentation.healthColor
import com.milkcocoa.info.sapphire.client.presentation.healthComparisonText
import com.milkcocoa.info.sapphire.client.presentation.formatBytes
import com.milkcocoa.info.sapphire.client.presentation.lifetimeRemainingColor
import com.milkcocoa.info.sapphire.client.presentation.policyProvenanceText
import com.milkcocoa.info.sapphire.client.presentation.protocolName
import com.milkcocoa.info.sapphire.client.presentation.temperatureColor
import com.milkcocoa.info.sapphire.client.presentation.warningColor
import com.milkcocoa.info.sapphire.client.presentation.wearColor
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot

@Composable
internal fun DeviceDetailPane(
    node: NodeSnapshot,
    snapshot: EvaluatedDiskSnapshot,
    historySourceKey: String,
    loadDeviceHistory: DeviceHistoryLoader,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(DeviceDetailTab.Current) }
    var historyState by remember(node.nodeId, snapshot.deviceKey, historySourceKey) {
        mutableStateOf<DeviceHistoryState>(DeviceHistoryState.Idle)
    }

    LaunchedEffect(selectedTab, node.nodeId, snapshot.deviceKey, historySourceKey) {
        if (selectedTab == DeviceDetailTab.History) {
            historyState = DeviceHistoryState.Loading
            historyState = runCatching {
                DeviceHistoryState.Loaded(
                    loadDeviceHistory(node.nodeId, snapshot.deviceKey),
                )
            }.getOrElse {
                DeviceHistoryState.Error(it.message ?: "Failed to load device history.")
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailHeader(node, snapshot)
        DetailTabs(
            selectedTab = selectedTab,
            onSelectTab = { selectedTab = it },
        )
        when (selectedTab) {
            DeviceDetailTab.Current -> CurrentDeviceDetail(
                node = node,
                snapshot = snapshot,
                modifier = Modifier.weight(1f),
            )

            DeviceDetailTab.History -> DeviceHistoryPane(
                state = historyState,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private enum class DeviceDetailTab(
    val label: String,
) {
    Current("Current"),
    History("History"),
}

@Composable
private fun DetailTabs(
    selectedTab: DeviceDetailTab,
    onSelectTab: (DeviceDetailTab) -> Unit,
) {
    PrimaryTabRow(
        selectedTabIndex = DeviceDetailTab.entries.indexOf(selectedTab),
        containerColor = Color.Transparent,
        contentColor = Color(0xFFE7E9EB),
    ) {
        DeviceDetailTab.entries.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun CurrentDeviceDetail(
    node: NodeSnapshot,
    snapshot: EvaluatedDiskSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FocusMetrics(node, snapshot)
        DetailInfoList(
            snapshot = snapshot,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailHeader(
    node: NodeSnapshot,
    snapshot: EvaluatedDiskSnapshot,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = snapshot.model ?: snapshot.path,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = snapshot.path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFA7ADB3),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HealthBadge(snapshot.health)
            }
            Text(
                text = "${node.nodeName} - ${node.nodeId} - Serial ${snapshot.serial ?: "-"}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF858C93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snapshot.evaluationPolicy.policyProvenanceText(),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA7ADB3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = snapshot.healthComparisonText(),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF858C93),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FocusMetrics(
    node: NodeSnapshot,
    snapshot: EvaluatedDiskSnapshot,
) {
    val universal = snapshot.metricsSnapshot.universal
    val lifetimeRemaining = universal.lifetimeRemainingPercent
    val lifeLabel = if (lifetimeRemaining != null) "Lifetime" else "Wear"
    val lifeValue = lifetimeRemaining?.let { "$it% left" }
        ?: universal.percentageUsed?.let { "$it% used" }
        ?: "-"
    val lifeAccent = lifetimeRemaining?.let { lifetimeRemainingColor(it) }
        ?: wearColor(universal.percentageUsed)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Focus")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HighlightMetric(
                label = "Evaluated Health",
                value = snapshot.health.name,
                accent = healthColor(snapshot.health),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Reported Health",
                value = snapshot.reportedHealth?.name ?: "Unknown",
                accent = snapshot.reportedHealth?.let(::healthColor) ?: Color(0xFF9AA1A8),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Temperature",
                value = snapshot.temperatureCelsius?.let { "$it C" } ?: "-",
                accent = temperatureColor(snapshot.temperatureCelsius),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = lifeLabel,
                value = lifeValue,
                accent = lifeAccent,
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Warnings",
                value = universal.criticalWarningCount?.toString() ?: "-",
                accent = warningColor(universal.criticalWarningCount),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HighlightMetric(
                label = "Power On",
                value = snapshot.powerOnHours?.let { "${it}h" } ?: "-",
                accent = Color(0xFF5EC4FF),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Capacity",
                value = formatBytes(snapshot.capacityBytes),
                accent = Color(0xFFA78BFA),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Protocol",
                value = snapshot.protocolName(),
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Device Key",
                value = snapshot.deviceKey,
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HighlightMetric(
                label = "Node",
                value = node.nodeName,
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Node ID",
                value = node.nodeId,
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Model",
                value = snapshot.model ?: "-",
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Serial",
                value = snapshot.serial ?: "-",
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HighlightMetric(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFA7ADB3),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
