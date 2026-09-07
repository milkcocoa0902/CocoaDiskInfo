package com.milkcocoa.info.sapphire.core.api

import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
/** Top-level result returned by the API: either a typed payload or an error. */
sealed interface ApiResponse {
    @Serializable
    /** Successful response carrying a domain-specific [payload]. */
    data class Success<T : ResponsePayload>(
        /** Response body for the requested operation. */
        val payload: T,
    ) : ApiResponse

    @Serializable
    /** Failed operation carrying machine-readable and human-readable error details. */
    data class Failure(
        /** Stable machine-readable error code and human-readable message. */
        val error: ApiError,
    ) : ApiResponse
}

/** Marker for payloads valid inside [ApiResponse.Success]. */
interface ResponsePayload

@Serializable
/** Stable error details returned by an API operation. */
data class ApiError(
    /** Machine-readable code suitable for client branching. */
    val code: String,
    /** Human-readable diagnostic; callers should not parse it for control flow. */
    val message: String,
)

@Serializable
/** Latest evaluated snapshots grouped by node, with partial-result and pagination metadata. */
data class LatestSnapshotsPayload(
    /** Nodes included in this page; a node may contain zero devices. */
    val nodes: List<NodeSnapshot>,
    /** Time at which the response view was generated, if known. */
    val generatedAt: Instant? = null,
    /** True when one or more requested nodes or devices are missing, stale, or have current errors. */
    val partial: Boolean = false,
    /** Per-node or request errors associated with a partial response. */
    val errors: List<NodeApiError> = emptyList(),
    /** True when the server omitted additional errors to bound response size. */
    val errorsTruncated: Boolean = false,
    /** Cursor metadata; null means this response is not paginated. */
    val pagination: PageMetadata? = null,
    /** The policy used consistently for every evaluated snapshot in this response. */
    val evaluationPolicy: HealthPolicyMetadata? = null,
) : ResponsePayload

@Serializable
/** Latest device views and freshness information for one node. */
data class NodeSnapshot(
    /** Stable node identifier. */
    val nodeId: String,
    /** Display name assigned to the node. */
    val nodeName: String,
    /** Evaluated disk snapshots currently associated with this node. */
    val devices: List<EvaluatedDiskSnapshot>,
    /** Administrative node state, when the server has a node record. */
    val status: NodeStatus? = null,
    /** Most recent node-level observation time, if known. */
    val lastSeenAt: Instant? = null,
    /** Freshness state for individual device streams. */
    val deviceStates: List<DeviceState> = emptyList(),
)

@Serializable
/** Freshness metadata for one node/device stream. */
data class DeviceState(
    /** Stable device key within the node. */
    val deviceKey: String,
    /** Timestamp of the most recently received snapshot. */
    val lastReceivedAt: Instant? = null,
    /** Age of the latest snapshot in milliseconds, if calculable. */
    val ageMs: Long? = null,
    /** Whether the stream exceeded the server's freshness policy. */
    val stale: Boolean = false,
)

@Serializable
/** Error associated with a node or with the request as a whole. */
data class NodeApiError(
    /** Stable machine-readable error code. */
    val code: String,
    /** Human-readable diagnostic message. */
    val message: String,
    /** Node affected by the error; null for request-wide errors. */
    val nodeId: String? = null,
)

@Serializable
/** Cursor-based pagination state for a list response. */
data class PageMetadata(
    /** Maximum number of entries requested for this page. */
    val limit: Int,
    /** Opaque cursor to send for the next page, if one exists. */
    val nextCursor: String? = null,
    /** Whether another page is available. */
    val hasMore: Boolean = false,
)

@Serializable
/** Administrative state of a registered node. */
enum class NodeStatus {
    /** Node may participate in collection and API reads. */
    ACTIVE,
    /** Node is retained but should not be treated as an active source. */
    DISABLED,
}

@Serializable
/** Historical evaluated snapshots for one node/device pair. */
data class NodeDeviceHistoryPayload(
    /** Stable node identifier. */
    val nodeId: String,
    /** Display name assigned to the node. */
    val nodeName: String,
    /** Stable device key within the node. */
    val deviceKey: String,
    /** Snapshots in the server-defined history order. */
    val snapshots: List<EvaluatedDiskSnapshot>,
    /** Administrative node state, when known. */
    val status: NodeStatus? = null,
    /** Most recent node-level observation time, if known. */
    val lastSeenAt: Instant? = null,
    /** Current freshness state for this device stream. */
    val deviceState: DeviceState? = null,
    /** Current error preventing a complete history response, if any. */
    val currentError: NodeApiError? = null,
    /** The policy used consistently for every evaluated snapshot in this response. */
    val evaluationPolicy: HealthPolicyMetadata? = null,
) : ResponsePayload
