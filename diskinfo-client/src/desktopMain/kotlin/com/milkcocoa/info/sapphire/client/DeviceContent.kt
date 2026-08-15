package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot

@Composable
internal fun DeviceContent(
    uiState: DeviceListState,
    historySourceKey: String,
    selectedNodeId: String?,
    selectedDeviceKey: String?,
    loadDeviceHistory: DeviceHistoryLoader,
    onSelectDevice: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        uiState.nodes.isNotEmpty() -> DeviceSnapshotContent(
            nodes = uiState.nodes,
            historySourceKey = historySourceKey,
            freshnessByDevice = uiState.presentation.freshnessByDevice,
            selectedNodeId = selectedNodeId,
            selectedDeviceKey = selectedDeviceKey,
            loadDeviceHistory = loadDeviceHistory,
            onSelectDevice = onSelectDevice,
            modifier = modifier,
        )

        uiState.isLoading -> LoadingDeviceList(modifier)
        uiState.message != null -> MessagePanel(
            title = "Agent unavailable",
            message = uiState.message,
            modifier = modifier,
        )

        else -> MessagePanel(
            title = "No disk snapshots",
            message = "The agent returned no collected nodes.",
            modifier = modifier,
        )
    }
}

@Composable
private fun DeviceSnapshotContent(
    nodes: List<NodeSnapshot>,
    historySourceKey: String,
    freshnessByDevice: Map<DeviceIdentity, FreshnessPresentation>,
    selectedNodeId: String?,
    selectedDeviceKey: String?,
    loadDeviceHistory: DeviceHistoryLoader,
    onSelectDevice: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedNode = nodes.firstOrNull { it.nodeId == selectedNodeId } ?: nodes.first()
    val selectedSnapshot = selectedNode.devices.firstOrNull { it.deviceKey == selectedDeviceKey }
        ?: selectedNode.devices.firstOrNull()

    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceListPane(
            nodes = nodes,
            freshnessByDevice = freshnessByDevice,
            selectedNodeId = selectedNode.nodeId,
            selectedDeviceKey = selectedDeviceKey,
            onSelectDevice = onSelectDevice,
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
        )
        if (selectedSnapshot == null) {
            MessagePanel(
                title = "No devices",
                message = "This node has no collected devices.",
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
            )
        } else {
            DeviceDetailPane(
                node = selectedNode,
                snapshot = selectedSnapshot,
                historySourceKey = historySourceKey,
                loadDeviceHistory = loadDeviceHistory,
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
            )
        }
    }
}
