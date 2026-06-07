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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import kotlinx.coroutines.launch

private const val DefaultAgentUrl = "http://localhost:14631"

@Composable
internal fun Dashboard(agentApiClient: AgentApiClient) {
    val scope = rememberCoroutineScope()
    var agentUrl by androidx.compose.runtime.remember { mutableStateOf(DefaultAgentUrl) }
    var uiState by androidx.compose.runtime.remember { mutableStateOf<DeviceListState>(DeviceListState.Loading) }
    var selectedNodeId by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    var selectedDeviceKey by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }

    fun refresh() {
        uiState = DeviceListState.Loading
        scope.launch {
            uiState = runCatching {
                val nodes = agentApiClient.fetchLatestNodes(agentUrl)
                val selectedStillExists = nodes.any { node ->
                    node.nodeId == selectedNodeId && node.devices.any { it.deviceKey == selectedDeviceKey }
                }
                if (!selectedStillExists) {
                    selectedNodeId = nodes.firstOrNull()?.nodeId
                    selectedDeviceKey = nodes.firstOrNull()?.devices?.firstOrNull()?.deviceKey
                }
                DeviceListState.Ready(nodes)
            }.getOrElse {
                DeviceListState.Failed(it.message ?: "Failed to load disk snapshots.")
            }
        }
    }

    LaunchedEffect(Unit) {
        refresh()
    }

    val nodes = (uiState as? DeviceListState.Ready)?.nodes.orEmpty()
    val devices = nodes.flatMap { it.devices }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header(
            agentUrl = agentUrl,
            onAgentUrlChange = { agentUrl = it },
            isLoading = uiState is DeviceListState.Loading,
            onRefresh = ::refresh,
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
            selectedNodeId = selectedNodeId,
            selectedDeviceKey = selectedDeviceKey,
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
    agentUrl: String,
    onAgentUrlChange: (String) -> Unit,
    isLoading: Boolean,
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
                    text = "Disk health dashboard",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFA7ADB3),
                )
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !isLoading,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = agentUrl,
                onValueChange = onAgentUrlChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Agent URL") },
            )
            Button(
                onClick = onRefresh,
                enabled = !isLoading,
                modifier = Modifier.height(56.dp),
            ) {
                Text("Connect")
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

internal sealed interface DeviceListState {
    data object Loading : DeviceListState

    data class Ready(
        val nodes: List<NodeSnapshot>,
    ) : DeviceListState

    data class Failed(
        val message: String,
    ) : DeviceListState
}
