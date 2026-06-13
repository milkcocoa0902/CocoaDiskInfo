package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DeviceContent(
    uiState: DeviceListState,
    selectedNodeId: String?,
    selectedDeviceKey: String?,
    onSelectDevice: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        is DeviceListState.Loading -> LoadingDeviceList(modifier)
        is DeviceListState.Failed -> MessagePanel(
            title = "Agent unavailable",
            message = uiState.message,
            modifier = modifier,
        )

        is DeviceListState.Ready -> {
            if (uiState.nodes.isEmpty()) {
                MessagePanel(
                    title = "No disk snapshots",
                    message = "The agent returned no collected nodes.",
                    modifier = modifier,
                )
            } else {
                val selectedNode = uiState.nodes.firstOrNull { it.nodeId == selectedNodeId }
                    ?: uiState.nodes.first()
                val selectedSnapshot = selectedNode.devices.firstOrNull { it.deviceKey == selectedDeviceKey }
                    ?: selectedNode.devices.firstOrNull()
                Row(
                    modifier = modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DeviceListPane(
                        nodes = uiState.nodes,
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
                            modifier = Modifier
                                .weight(0.55f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}
