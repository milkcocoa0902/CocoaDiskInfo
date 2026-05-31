package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import kotlinx.coroutines.launch

private const val DefaultAgentUrl = "http://localhost:14631"

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CocoaDiskInfo",
    ) {
        CocoaDiskInfoApp()
    }
}

@Composable
private fun CocoaDiskInfoApp(
    agentApiClient: AgentApiClient = remember { AgentApiClient() },
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF5EC4FF),
            secondary = Color(0xFF62D6A4),
            surface = Color(0xFF171717),
            background = Color(0xFF101010),
            surfaceVariant = Color(0xFF232527),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                AppNavigation()
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = Color(0xFF303236),
                )
                Dashboard(agentApiClient)
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    NavigationRail(
        modifier = Modifier.width(76.dp),
        containerColor = Color(0xFF151515),
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        NavigationRailItem(
            selected = true,
            onClick = {},
            icon = { Icon(Icons.Outlined.Storage, contentDescription = "Disks") },
        )
    }
}

@Composable
private fun Dashboard(agentApiClient: AgentApiClient) {
    val scope = rememberCoroutineScope()
    var agentUrl by remember { mutableStateOf(DefaultAgentUrl) }
    var uiState by remember { mutableStateOf<DeviceListState>(DeviceListState.Loading) }
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var selectedDeviceKey by remember { mutableStateOf<String?>(null) }

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

@Composable
private fun DeviceContent(
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
                    DeviceList(
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

@Composable
private fun DeviceList(
    nodes: List<NodeSnapshot>,
    selectedNodeId: String?,
    selectedDeviceKey: String?,
    onSelectDevice: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        nodes.forEach { node ->
            item(key = "node-${node.nodeId}") {
                NodeHeader(node)
            }
            items(
                items = node.devices,
                key = { "${node.nodeId}-${it.deviceKey}" },
            ) { snapshot ->
                DeviceRow(
                    snapshot = snapshot,
                    selected = node.nodeId == selectedNodeId && snapshot.deviceKey == selectedDeviceKey,
                    onClick = { onSelectDevice(node.nodeId, snapshot.deviceKey) },
                )
            }
        }
    }
}

@Composable
private fun NodeHeader(node: NodeSnapshot) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = node.nodeName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "${node.devices.size} devices - ${node.nodeId}",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF858C93),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceRow(
    snapshot: DiskSnapshot,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF22313A) else Color(0xFF171717),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = healthColor(snapshot.health),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = snapshot.model ?: snapshot.path,
                    style = MaterialTheme.typography.titleMedium,
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
                Text(
                    text = "Serial ${snapshot.serial ?: "-"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF858C93),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            HealthBadge(snapshot.health)
        }
    }
}

@Composable
private fun DeviceDetailPane(
    node: NodeSnapshot,
    snapshot: DiskSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DetailHeader(node, snapshot)
        FocusMetrics(snapshot)
        DetailInfoList(
            node = node,
            snapshot = snapshot,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DetailHeader(
    node: NodeSnapshot,
    snapshot: DiskSnapshot,
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
        }
    }
}

@Composable
private fun FocusMetrics(snapshot: DiskSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Focus")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HighlightMetric(
                label = "Health",
                value = snapshot.health.name,
                accent = healthColor(snapshot.health),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Temperature",
                value = snapshot.temperatureCelsius?.let { "$it C" } ?: "-",
                accent = temperatureColor(snapshot.temperatureCelsius),
                modifier = Modifier.weight(1f),
            )
            HighlightMetric(
                label = "Wear",
                value = snapshot.metricsSnapshot.universal.percentageUsed?.let { "$it%" } ?: "-",
                accent = wearColor(snapshot.metricsSnapshot.universal.percentageUsed),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HighlightMetric(
                label = "Warnings",
                value = snapshot.metricsSnapshot.universal.criticalWarningCount?.toString() ?: "-",
                accent = warningColor(snapshot.metricsSnapshot.universal.criticalWarningCount),
                modifier = Modifier.weight(1f),
            )
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
        modifier = modifier.height(92.dp),
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
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailInfoList(
    node: NodeSnapshot,
    snapshot: DiskSnapshot,
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
                items(detailRows(node, snapshot)) { row ->
                    DetailRow(row.label, row.value)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(152.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF858C93),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE7E9EB),
        )
    }
}

@Composable
private fun HealthBadge(health: DiskHealth) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = healthColor(health).copy(alpha = 0.16f),
        contentColor = healthColor(health),
    ) {
        Text(
            text = health.name,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LoadingDeviceList(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = "Loading disk snapshots",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFA7ADB3),
            )
        }
    }
}

@Composable
private fun MessagePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = Color(0xFF68727C),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE7E9EB),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9AA1A8),
            )
        }
    }
}

private sealed interface DeviceListState {
    data object Loading : DeviceListState

    data class Ready(
        val nodes: List<NodeSnapshot>,
    ) : DeviceListState

    data class Failed(
        val message: String,
    ) : DeviceListState
}

private fun List<DiskSnapshot>.countWarnings(): Int {
    return count { it.health == DiskHealth.CAUTION || it.health == DiskHealth.BAD }
}

private fun List<DiskSnapshot>.latestTimestamp(): String {
    return maxByOrNull { it.timestamp }?.timestamp?.toString()?.substringBefore('.') ?: "--"
}

private fun DiskSnapshot.protocolName(): String {
    return when (metricsSnapshot) {
        is MetricsSnapshot.AtaMetricsSnapshot -> "ATA"
        is MetricsSnapshot.NvmeMetricsSnapshot -> "NVMe"
    }
}

private fun detailRows(
    node: NodeSnapshot,
    snapshot: DiskSnapshot,
): List<DetailRowValue> {
    val universal = snapshot.metricsSnapshot.universal
    return buildList {
        add("Node ID", node.nodeId)
        add("Node name", node.nodeName)
        add("Device key", snapshot.deviceKey)
        add("Path", snapshot.path)
        add("Model", snapshot.model ?: "-")
        add("Serial", snapshot.serial ?: "-")
        add("Protocol", snapshot.protocolName())
        add("Timestamp", snapshot.timestamp.toString())
        add("Capacity", "${formatBytes(snapshot.capacityBytes)} (${snapshot.capacityBytes} bytes)")
        add("Health", snapshot.health.name)
        add("Temperature", snapshot.temperatureCelsius?.let { "$it C" } ?: "-")
        add("Power on hours", snapshot.powerOnHours?.toString() ?: "-")
        add("Power cycles", universal.powerCycleCount?.toString() ?: "-")
        add("Percentage used", universal.percentageUsed?.let { "$it%" } ?: "-")
        add("Total bytes written", universal.totalBytesWritten?.let { formatBytes(it) } ?: "-")
        add("Total bytes read", universal.totalBytesRead?.let { formatBytes(it) } ?: "-")
        add("Critical warnings", universal.criticalWarningCount?.toString() ?: "-")

        when (val metrics = snapshot.metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> {
                add("ATA attributes", metrics.attributes.size.toString())
                metrics.attributes.forEach { attribute ->
                    add(attribute.displayLabel(), attribute.displayValue())
                }
            }

            is MetricsSnapshot.NvmeMetricsSnapshot -> {
                add("Available spare", metrics.availableSpare?.let { "$it%" } ?: "-")
                add("NVMe percentage used", metrics.percentageUsed?.let { "$it%" } ?: "-")
                add("Media errors", metrics.mediaErrors?.toString() ?: "-")
                add("Data units written", metrics.dataUnitsWritten?.toString() ?: "-")
                add("Data units read", metrics.dataUnitsRead?.toString() ?: "-")
            }
        }
    }
}

private fun MutableList<DetailRowValue>.add(
    label: String,
    value: String,
) {
    add(DetailRowValue(label, value))
}

private fun AtaAttribute.displayLabel(): String {
    return "SMART ${id.id} ${id.name}"
}

private fun AtaAttribute.displayValue(): String {
    return "value=$value, worst=$worst, threshold=$threshold, raw=${rawString.ifBlank { rawValue.toString() }}"
}

private fun healthColor(health: DiskHealth): Color {
    return when (health) {
        DiskHealth.GOOD -> Color(0xFF62D6A4)
        DiskHealth.CAUTION -> Color(0xFFFBBF24)
        DiskHealth.BAD -> Color(0xFFFF6B6B)
        DiskHealth.UNKNOWN -> Color(0xFF9AA1A8)
    }
}

private fun temperatureColor(temperatureCelsius: Int?): Color {
    return when {
        temperatureCelsius == null -> Color(0xFF9AA1A8)
        temperatureCelsius >= 60 -> Color(0xFFFF6B6B)
        temperatureCelsius >= 50 -> Color(0xFFFBBF24)
        else -> Color(0xFF62D6A4)
    }
}

private fun wearColor(percentageUsed: Int?): Color {
    return when {
        percentageUsed == null -> Color(0xFF9AA1A8)
        percentageUsed >= 90 -> Color(0xFFFF6B6B)
        percentageUsed >= 70 -> Color(0xFFFBBF24)
        else -> Color(0xFF62D6A4)
    }
}

private fun warningColor(criticalWarningCount: Int?): Color {
    return when {
        criticalWarningCount == null -> Color(0xFF9AA1A8)
        criticalWarningCount > 0 -> Color(0xFFFF6B6B)
        else -> Color(0xFF62D6A4)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var unitIndex = -1

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return "%.1f %s".format(value, units[unitIndex])
}

private data class DetailRowValue(
    val label: String,
    val value: String,
)
