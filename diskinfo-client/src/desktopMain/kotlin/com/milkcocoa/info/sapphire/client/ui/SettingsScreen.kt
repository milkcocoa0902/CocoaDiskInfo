package com.milkcocoa.info.sapphire.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.milkcocoa.info.sapphire.client.api.ClientPairingClient
import com.milkcocoa.info.sapphire.client.api.ClientPairingMaterial
import com.milkcocoa.info.sapphire.client.profile.AgentUrlStore
import com.milkcocoa.info.sapphire.client.profile.ConnectionProfile
import com.milkcocoa.info.sapphire.client.profile.LEGACY_CONNECTION_PROFILE_ID
import com.milkcocoa.info.sapphire.client.profile.PreferencesConnectionProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.Locale

@Composable
internal fun SettingsScreen() {
    val profileStore = remember { PreferencesConnectionProfileStore() }
    val initialProfileResult = remember { runCatching(profileStore::load) }
    val initialProfile = remember {
        initialProfileResult.getOrElse {
            ConnectionProfile(
                id = LEGACY_CONNECTION_PROFILE_ID,
                name = "Legacy Agent",
                baseUrl = PreferencesConnectionProfileStore.DEFAULT_AGENT_URL,
                credentialPath = null,
            )
        }
    }
    var profile by remember { mutableStateOf(initialProfile) }
    var profileName by remember { mutableStateOf(initialProfile.name) }
    var baseUrl by remember { mutableStateOf(initialProfile.baseUrl) }
    var allowInsecureTransport by remember { mutableStateOf(initialProfile.allowInsecureTransport) }
    var credentialPath by remember { mutableStateOf(initialProfile.credentialPath.orEmpty()) }
    var pemCaPath by remember { mutableStateOf(initialProfile.pemCaPath.orEmpty()) }
    var hubId by remember {
        mutableStateOf(initialProfile.id.takeUnless { it == LEGACY_CONNECTION_PROFILE_ID }.orEmpty())
    }
    var pairingTokenId by remember { mutableStateOf("") }
    var pairingTokenSecret by remember { mutableStateOf("") }
    var isPairing by remember { mutableStateOf(false) }
    var refreshInterval by remember { mutableStateOf(AgentUrlStore.refreshIntervalSeconds.toString()) }
    var statusMessage by remember {
        mutableStateOf(initialProfileResult.exceptionOrNull()?.message)
    }
    var statusIsSuccess by remember {
        mutableStateOf<Boolean?>(initialProfileResult.exceptionOrNull()?.let { false })
    }
    val pairingClient = remember {
        ClientPairingClient(profileStore = profileStore)
    }
    val scope = rememberCoroutineScope()
    val isHttp = runCatching {
        URI(baseUrl.trim()).scheme?.lowercase(Locale.ROOT) == "http"
    }.getOrDefault(false)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                value = profileName,
                onValueChange = { profileName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Profile name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hub or Standalone URL") },
                singleLine = true,
                placeholder = { Text("https://hub.example") },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = allowInsecureTransport,
                    onCheckedChange = { allowInsecureTransport = it },
                )
                Text("Allow insecure HTTP transport")
            }
            if (isHttp) {
                Text(
                    text = if (allowInsecureTransport) {
                        "HTTP has no server authentication, confidentiality, or response integrity."
                    } else {
                        "HTTP is blocked until insecure transport is explicitly allowed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB4A9),
                )
            }
            OutlinedTextField(
                value = credentialPath,
                onValueChange = { credentialPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Client credential file") },
                singleLine = true,
            )
            if (credentialPath.isBlank()) {
                Text(
                    text = "Client credential registration is required before signed reads can be used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB4A9),
                )
            }
            OutlinedTextField(
                value = pemCaPath,
                onValueChange = { pemCaPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Additional PEM CA file (optional)") },
                singleLine = true,
            )
            Text(
                text = "Client pairing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = hubId,
                onValueChange = { hubId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hub ID") },
                singleLine = true,
            )
            OutlinedTextField(
                value = pairingTokenId,
                onValueChange = { pairingTokenId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing token ID") },
                singleLine = true,
            )
            OutlinedTextField(
                value = pairingTokenSecret,
                onValueChange = { pairingTokenSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Pairing token secret") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                text = "The pairing token secret is kept only for this attempt.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFA7ADB3),
            )
            Button(
                enabled = !isPairing,
                onClick = {
                    val pairingMaterial = ClientPairingMaterial(
                        endpoint = baseUrl,
                        hubId = hubId,
                        tokenId = pairingTokenId,
                        tokenSecret = pairingTokenSecret,
                        displayName = profileName,
                        allowInsecureTransport = allowInsecureTransport,
                        pemCaPath = pemCaPath.ifBlank { null },
                        credentialPath = credentialPath,
                    )
                    // token secretを画面stateへ試行後も残さない。
                    pairingTokenSecret = ""
                    pairingTokenId = ""
                    isPairing = true
                    statusMessage = "Pairing client..."
                    statusIsSuccess = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                pairingClient.pair(pairingMaterial)
                            }
                        }.onSuccess { result ->
                            profile = result.profile
                            hubId = result.profile.id
                            profileName = result.profile.name
                            baseUrl = result.profile.baseUrl
                            allowInsecureTransport = result.profile.allowInsecureTransport
                            credentialPath = result.profile.credentialPath.orEmpty()
                            pemCaPath = result.profile.pemCaPath.orEmpty()
                            statusMessage = "Client paired successfully. Key ID: ${result.kid}"
                            statusIsSuccess = true
                        }.onFailure {
                            statusMessage = it.message ?: "Client pairing failed."
                            statusIsSuccess = false
                        }
                        isPairing = false
                    }
                },
            ) {
                Text(if (isPairing) "Pairing..." else "Pair this client")
            }
            Button(
                enabled = !isPairing,
                onClick = {
                    val updated = profile.copy(
                        name = profileName,
                        baseUrl = baseUrl,
                        allowInsecureTransport = allowInsecureTransport,
                        credentialPath = credentialPath.ifBlank { null },
                        pemCaPath = pemCaPath.ifBlank { null },
                    )
                    runCatching { profileStore.save(updated) }
                        .onSuccess {
                            profile = updated
                            statusMessage = "Connection profile saved."
                            statusIsSuccess = true
                        }
                        .onFailure {
                            statusMessage = it.message ?: "Failed to save connection profile."
                            statusIsSuccess = false
                        }
                },
            ) {
                Text("Save connection profile")
            }
            statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (statusIsSuccess) {
                        true -> Color(0xFF62D6A4)
                        false -> Color(0xFFFFB4A9)
                        null -> Color(0xFFA7ADB3)
                    },
                )
            }
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
                suffix = { Text("sec") },
            )
        }
    }
}
