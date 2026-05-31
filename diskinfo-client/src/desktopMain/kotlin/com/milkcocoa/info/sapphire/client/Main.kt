package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "CocoaDiskInfo",
    ) {
        CocoaDiskInfoApp()
    }
}

@Composable
private fun CocoaDiskInfoApp() {
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
                    modifier = Modifier
                        .fillMaxHeight(),
                    thickness = 1.dp,
                    color = Color(0xFF303236),
                )
                Dashboard()
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
private fun Dashboard() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Header()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SummaryCard(title = "Devices", value = "0", modifier = Modifier.weight(1f))
            SummaryCard(title = "Warnings", value = "0", modifier = Modifier.weight(1f))
            SummaryCard(title = "Last Scan", value = "--", modifier = Modifier.weight(1f))
        }
        EmptyDeviceList()
    }
}

@Composable
private fun Header() {
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
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = {}) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
            Button(onClick = {}) {
                Text("Scan")
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
            )
        }
    }
}

@Composable
private fun EmptyDeviceList() {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171717)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
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
                    text = "No disk snapshots",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE7E9EB),
                )
                Text(
                    text = "No collection has run in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9AA1A8),
                )
            }
        }
    }
}
