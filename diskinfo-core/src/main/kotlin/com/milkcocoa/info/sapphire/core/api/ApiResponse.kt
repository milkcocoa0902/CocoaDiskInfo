package com.milkcocoa.info.sapphire.core.api

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.Serializable

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
) : ResponsePayload

@Serializable
data class NodeSnapshot(
    val nodeId: String,
    val nodeName: String,
    val devices: List<DiskSnapshot>,
)

@Serializable
data class NodeDeviceHistoryPayload(
    val nodeId: String,
    val nodeName: String,
    val deviceKey: String,
    val snapshots: List<DiskSnapshot>,
) : ResponsePayload
