package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

@Composable
internal fun DeviceListPane(
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
