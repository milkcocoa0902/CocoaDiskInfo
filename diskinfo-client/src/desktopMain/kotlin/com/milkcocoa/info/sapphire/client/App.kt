package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun CocoaDiskInfoApp(
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
