package com.milkcocoa.info.sapphire.core.api

import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
sealed interface ApiResponse {
    @Serializable
    data class Success<T : ResponsePayload>(
        val payload: T,
    ) : ApiResponse

    @Serializable
    data class Failure(
        val error: ApiError,
    ) : ApiResponse
}

interface ResponsePayload

@Serializable
data class ApiError(
    val code: String,
    val message: String,
)

@Serializable
data class LatestSnapshotsPayload(
    val nodes: List<NodeSnapshot>,
    val generatedAt: Instant? = null,
    val partial: Boolean = false,
    val errors: List<NodeApiError> = emptyList(),
    val errorsTruncated: Boolean = false,
    val pagination: PageMetadata? = null,
    /** The policy used consistently for every evaluated snapshot in this response. */
    val evaluationPolicy: HealthPolicyMetadata? = null,
) : ResponsePayload

@Serializable
data class NodeSnapshot(
    val nodeId: String,
    val nodeName: String,
    val devices: List<EvaluatedDiskSnapshot>,
    val status: NodeStatus? = null,
    val lastSeenAt: Instant? = null,
    val deviceStates: List<DeviceState> = emptyList(),
)

@Serializable
data class DeviceState(
    val deviceKey: String,
    val lastReceivedAt: Instant? = null,
    val ageMs: Long? = null,
    val stale: Boolean = false,
)

@Serializable
data class NodeApiError(
    val code: String,
    val message: String,
    val nodeId: String? = null,
)

@Serializable
data class PageMetadata(
    val limit: Int,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
enum class NodeStatus {
    ACTIVE,
    DISABLED,
}

@Serializable
data class NodeDeviceHistoryPayload(
    val nodeId: String,
    val nodeName: String,
    val deviceKey: String,
    val snapshots: List<EvaluatedDiskSnapshot>,
    val status: NodeStatus? = null,
    val lastSeenAt: Instant? = null,
    val deviceState: DeviceState? = null,
    val currentError: NodeApiError? = null,
    /** The policy used consistently for every evaluated snapshot in this response. */
    val evaluationPolicy: HealthPolicyMetadata? = null,
) : ResponsePayload
