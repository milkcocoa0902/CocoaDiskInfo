package com.milkcocoa.info.sapphire.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen() {
    var agentUrl by remember { mutableStateOf(AgentUrlStore.agentUrl) }
    var refreshInterval by remember { mutableStateOf(AgentUrlStore.refreshIntervalSeconds.toString()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Configure application behavior",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFA7ADB3),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Connection",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = agentUrl,
                onValueChange = {
                    agentUrl = it
                    AgentUrlStore.agentUrl = it
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Agent URL") },
                singleLine = true,
                placeholder = { Text("http://localhost:14631") }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "Appearance & Behavior",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            OutlinedTextField(
                value = refreshInterval,
                onValueChange = { newValue ->
                    if (newValue.all { it.isDigit() }) {
                        refreshInterval = newValue
                        newValue.toLongOrNull()?.let {
                            AgentUrlStore.refreshIntervalSeconds = it
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Auto Refresh Interval (seconds)") },
                singleLine = true,
                suffix = { Text("sec") }
            )
        }
    }
}
