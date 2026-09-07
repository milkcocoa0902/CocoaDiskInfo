package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
/** Hub ingest body; [ingestId] provides retry idempotency for the authenticated node. */
data class SnapshotIngestRequest(
    /** Caller-generated idempotency key, validated as a UUID by the Hub route. */
    val ingestId: String,
    /** Raw snapshot observed by the Node Agent. */
    val snapshot: DiskSnapshot,
)

@Serializable
/** Whether an ingest inserted a row or matched an existing idempotent delivery. */
enum class SnapshotIngestStatus {
    /** The snapshot was newly stored. */
    STORED,
    /** The ingest id was already stored with the same content. */
    DUPLICATE,
}

@Serializable
/** Result of an authenticated snapshot ingest. */
data class SnapshotIngestResponse(
    /** Echo of the request idempotency key. */
    val ingestId: String,
    /** Insert/duplicate outcome. */
    val status: SnapshotIngestStatus,
    /** Stored snapshot row identity. */
    val snapshotId: String,
    /** Hub receive timestamp, not the device collection timestamp. */
    val receivedAt: Instant,
)

@Serializable
/** Node liveness report and expected collection cadence. */
data class NodeHeartbeatRequest(
    /** Positive interval used to determine freshness. */
    val expectedCollectionIntervalSeconds: Long,
    /** Optional current collection failure; code and message must be supplied together. */
    val error: NodeReportedError? = null,
)

@Serializable
/** Error state reported by a Node Agent heartbeat. */
data class NodeReportedError(
    /** Stable machine-readable error code. */
    val code: String,
    /** Human-readable diagnostic message. */
    val message: String,
)

@Serializable
/** Acknowledgement timestamp for a heartbeat. */
data class NodeHeartbeatResponse(
    /** Hub receive timestamp. */
    val receivedAt: Instant,
)

@Serializable
/** Proof-of-possession request for registering a Node Agent principal. */
data class NodeJoinRequest(
    /** UUID of the one-time join token. */
    val joinTokenId: String,
    /** Nonce issued for the join token and JOIN purpose. */
    val nonce: String,
    /** New Ed25519 public key. */
    val publicKey: Ed25519PublicJwk,
    /** Identifier derived from [publicKey]. */
    val kid: String,
    /** Registered node display name. */
    val nodeName: String,
    /** Positive cadence expected from the node. */
    val expectedCollectionIntervalSeconds: Long,
    /** HMAC proof over the canonical Hub/token/nonce/key/name input. */
    val proof: String,
)

@Serializable
/** Identities assigned after a successful Node Agent join. */
data class NodeJoinResponse(
    /** Stable Hub identity confirmed by the joiner. */
    val hubId: String,
    /** Hub principal row identity. */
    val principalId: String,
    /** Authoritative node identity used for ingest/history scoping. */
    val nodeId: String,
    /** Identifier of the accepted public key. */
    val kid: String,
)

@Serializable
/** Proof-of-possession request for registering a client principal. */
data class ClientPairRequest(
    /** UUID of the one-time pairing token. */
    val pairingTokenId: String,
    /** Nonce issued for the pairing token and CLIENT_PAIR purpose. */
    val nonce: String,
    /** New client Ed25519 public key. */
    val publicKey: Ed25519PublicJwk,
    /** Identifier derived from [publicKey]. */
    val kid: String,
    /** Registered client display name. */
    val displayName: String,
    /** HMAC proof over the canonical Hub/token/nonce/key/name input. */
    val proof: String,
)

@Serializable
/** Identities assigned after successful client pairing. */
data class ClientPairResponse(
    /** Stable Hub identity returned to the client. */
    val hubId: String,
    /** Hub principal row identity. */
    val principalId: String,
    /** Identifier of the accepted public key. */
    val kid: String,
)

@Serializable
/** Human-transferable bootstrap material; the secret must be handled as a credential. */
data class BootstrapTokenMaterial(
    /** Material format version. */
    val version: Int = 1,
    /** Join or pairing token type. */
    val type: String,
    /** Host endpoint used for bootstrap. */
    val endpoint: String,
    /** Stable Hub identity expected by the joiner. */
    val hubId: String,
    /** One-time token UUID. */
    val tokenId: String,
    /** Base64url secret used to create the proof. */
    val tokenSecret: String,
    /** Token expiry advertised to the recipient. */
    val expiresAt: Instant,
)
