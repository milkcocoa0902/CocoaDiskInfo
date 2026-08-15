package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun Dashboard(agentApiClient: AgentApiClient) {
    val scope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(DeviceListState(isLoading = true)) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedDeviceKey by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        uiState = uiState.copy(
            isLoading = true,
            message = null,
        )

        uiState = runCatching {
            val payload = agentApiClient.fetchLatestSnapshots()
            val nodes = payload.nodes
            val selectedStillExists = nodes.any { node ->
                node.nodeId == selectedNodeId && node.devices.any { it.deviceKey == selectedDeviceKey }
            }
            if (!selectedStillExists) {
                selectedNodeId = nodes.firstOrNull()?.nodeId
                selectedDeviceKey = nodes.firstOrNull()?.devices?.firstOrNull()?.deviceKey
            }
            uiState.copy(
                isLoading = false,
                nodes = nodes,
                presentation = payload.toPresentationState(),
            )
        }.getOrElse {
            val message = it.message ?: "Failed to load disk snapshots."
            uiState.copy(
                isLoading = false,
                message = message,
            )
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            val interval = AgentUrlStore.refreshIntervalSeconds
            if (interval > 0) {
                delay((interval * 1000).milliseconds)
            } else {
                break
            }
        }
    }

    val nodes = uiState.nodes
    val devices = nodes.flatMap { it.devices }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(
            isRefreshing = uiState.isLoading,
            lastError = uiState.message,
            presentation = uiState.presentation,
            onRefresh = { scope.launch { refresh() } },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryCard(title = "Nodes", value = nodes.size.toString(), modifier = Modifier.weight(1f))
            SummaryCard(title = "Devices", value = devices.size.toString(), modifier = Modifier.weight(1f))
            SummaryCard(title = "Warnings", value = devices.countWarnings().toString(), modifier = Modifier.weight(1f))
            SummaryCard(title = "Last Scan", value = devices.latestTimestamp(), modifier = Modifier.weight(1f))
        }
        DeviceContent(
            uiState = uiState,
            historySourceKey = AgentUrlStore.agentUrl,
            selectedNodeId = selectedNodeId,
            selectedDeviceKey = selectedDeviceKey,
            loadDeviceHistory = { nodeId, deviceKey ->
                agentApiClient.fetchDeviceHistory(
                    nodeId = nodeId,
                    deviceKey = deviceKey,
                )
            },
            onSelectDevice = { nodeId, deviceKey ->
                selectedNodeId = nodeId
                selectedDeviceKey = deviceKey
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun Header(
    isRefreshing: Boolean,
    lastError: String?,
    presentation: LatestSnapshotPresentationState,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "CocoaDiskInfo",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        lastError != null -> "Last refresh failed: $lastError"
                        isRefreshing -> "Refreshing disk snapshots"
                        presentation.partial -> presentation.errors.firstOrNull()?.let {
                            "Partial data: ${it.message}${if (presentation.errorsTruncated) " (more errors)" else ""}"
                        } ?: "Partial data returned by the agent"
                        presentation.freshnessByDevice.values.any { it.level == FreshnessLevel.STALE } ->
                            "Cached data includes stale snapshots"
                        else -> "Disk health dashboard"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (lastError == null) Color(0xFFA7ADB3) else Color(0xFFFFB4A9),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isRefreshing,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRefreshing) "Refreshing" else "Refresh")
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.height(112.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFA7ADB3),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


internal data class DeviceListState(
    val isLoading: Boolean = false,
    val nodes: List<NodeSnapshot> = emptyList(),
    val message: String? = null,
    val presentation: LatestSnapshotPresentationState = LatestSnapshotPresentationState(
        partial = false,
        errors = emptyList(),
        errorsTruncated = false,
        freshnessByDevice = emptyMap(),
    ),
)

internal typealias DeviceHistoryLoader = suspend (String, String) -> NodeDeviceHistoryPayload
