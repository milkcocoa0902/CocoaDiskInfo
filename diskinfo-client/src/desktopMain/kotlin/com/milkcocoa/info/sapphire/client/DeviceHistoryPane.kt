package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

internal sealed interface DeviceHistoryState {
    data object Idle : DeviceHistoryState
    data object Loading : DeviceHistoryState
    data class Loaded(val payload: NodeDeviceHistoryPayload) : DeviceHistoryState
    data class Error(val message: String) : DeviceHistoryState
}

@Composable
internal fun DeviceHistoryPane(
    state: DeviceHistoryState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        DeviceHistoryState.Idle,
        DeviceHistoryState.Loading -> LoadingHistory(modifier)

        is DeviceHistoryState.Error -> MessagePanel(
            title = "History unavailable",
            message = state.message,
            modifier = modifier,
        )

        is DeviceHistoryState.Loaded -> {
            val snapshots = state.payload.snapshots
            if (snapshots.isEmpty()) {
                MessagePanel(
                    title = "No history",
                    message = "No snapshots have been stored for this device.",
                    modifier = modifier,
                )
            } else {
                HistoryContent(
                    snapshots = snapshots,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun LoadingHistory(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Loading history",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFA7ADB3),
        )
    }
}

@Composable
private fun HistoryContent(
    snapshots: List<DiskSnapshot>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HistorySummary(snapshots)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Timeline")
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(snapshots) { snapshot ->
                    HistoryTimelineRow(snapshot)
                }
            }
        }
    }
}

@Composable
private fun HistorySummary(snapshots: List<DiskSnapshot>) {
    val firstSnapshot = snapshots.minBy { it.timestamp }
    val latestSnapshot = snapshots.maxBy { it.timestamp }
    val maxTemperature = snapshots.mapNotNull { it.temperatureCelsius }.maxOrNull()
    val worstHealth = snapshots.maxBy { it.health.rank() }.health

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("History")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HistorySummaryTile(
                label = "Snapshots",
                value = snapshots.size.toString(),
                accent = Color(0xFFE7E9EB),
                modifier = Modifier.weight(1f),
            )
            HistorySummaryTile(
                label = "Max Temp",
                value = maxTemperature?.let { "$it C" } ?: "-",
                accent = temperatureColor(maxTemperature),
                modifier = Modifier.weight(1f),
            )
            HistorySummaryTile(
                label = "Worst",
                value = worstHealth.name,
                accent = healthColor(worstHealth),
                modifier = Modifier.weight(1f),
            )
        }
        HistoryRangeTile(
            oldest = firstSnapshot.timestampLabel(),
            latest = latestSnapshot.timestampLabel(),
        )
    }
}

@Composable
private fun HistorySummaryTile(
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

@Composable
private fun HistoryRangeTile(
    oldest: String,
    latest: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
        ) {
            RangeEndpoint(
                label = "Oldest",
                value = oldest,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = Color(0xFFA7ADB3),
            modifier = Modifier.size(18.dp),
        )

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
        ) {
            RangeEndpoint(
                label = "Latest",
                value = latest,
            )
        }
    }
}

@Composable
private fun RangeEndpoint(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA7ADB3),
            modifier = Modifier.weight(0.32f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE7E9EB),
            modifier = Modifier.weight(0.68f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HistoryTimelineRow(snapshot: DiskSnapshot) {
    val universal = snapshot.metricsSnapshot.universal
    val lifeValue = universal.lifetimeRemainingPercent?.let { "$it% left" }
        ?: universal.percentageUsed?.let { "$it% used" }
        ?: "-"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimelineCell(
                label = "Time",
                value = snapshot.timestampLabel(),
                modifier = Modifier.weight(1.5f),
            )
            TimelineCell(
                label = "Health",
                value = snapshot.health.name,
                color = healthColor(snapshot.health),
                modifier = Modifier.weight(0.9f),
            )
            TimelineCell(
                label = "Temp",
                value = snapshot.temperatureCelsius?.let { "$it C" } ?: "-",
                color = temperatureColor(snapshot.temperatureCelsius),
                modifier = Modifier.weight(0.8f),
            )
            TimelineCell(
                label = "Life",
                value = lifeValue,
                color = universal.lifetimeRemainingPercent?.let { lifetimeRemainingColor(it) }
                    ?: wearColor(universal.percentageUsed),
                modifier = Modifier.weight(0.9f),
            )
            TimelineCell(
                label = "Warnings",
                value = universal.criticalWarningCount?.toString() ?: "-",
                color = warningColor(universal.criticalWarningCount),
                modifier = Modifier.weight(0.8f),
            )
        }
    }
}

@Composable
private fun TimelineCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFFE7E9EB),
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFA7ADB3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun DiskHealth.rank(): Int {
    return when (this) {
        DiskHealth.GOOD -> 0
        DiskHealth.UNKNOWN -> 1
        DiskHealth.CAUTION -> 2
        DiskHealth.BAD -> 3
    }
}
