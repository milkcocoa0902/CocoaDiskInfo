package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class SnapshotIngestRequest(
    val ingestId: String,
    val snapshot: DiskSnapshot,
)

@Serializable
enum class SnapshotIngestStatus {
    STORED,
    DUPLICATE,
}

@Serializable
data class SnapshotIngestResponse(
    val ingestId: String,
    val status: SnapshotIngestStatus,
    val snapshotId: String,
    val receivedAt: Instant,
)

@Serializable
data class NodeHeartbeatRequest(
    val expectedCollectionIntervalSeconds: Long,
    val error: NodeReportedError? = null,
)

@Serializable
data class NodeReportedError(
    val code: String,
    val message: String,
)

@Serializable
data class NodeHeartbeatResponse(
    val receivedAt: Instant,
)

@Serializable
data class NodeJoinRequest(
    val joinTokenId: String,
    val nonce: String,
    val publicKey: Ed25519PublicJwk,
    val kid: String,
    val nodeName: String,
    val expectedCollectionIntervalSeconds: Long,
    val proof: String,
)

@Serializable
data class NodeJoinResponse(
    val hubId: String,
    val principalId: String,
    val nodeId: String,
    val kid: String,
)

@Serializable
data class ClientPairRequest(
    val pairingTokenId: String,
    val nonce: String,
    val publicKey: Ed25519PublicJwk,
    val kid: String,
    val displayName: String,
    val proof: String,
)

@Serializable
data class ClientPairResponse(
    val hubId: String,
    val principalId: String,
    val kid: String,
)

@Serializable
data class BootstrapTokenMaterial(
    val version: Int = 1,
    val type: String,
    val endpoint: String,
    val hubId: String,
    val tokenId: String,
    val tokenSecret: String,
    val expiresAt: Instant,
)
