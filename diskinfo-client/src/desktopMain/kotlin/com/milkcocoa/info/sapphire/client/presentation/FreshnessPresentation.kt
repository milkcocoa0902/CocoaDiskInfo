package com.milkcocoa.info.sapphire.client.presentation

import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError

internal enum class FreshnessLevel {
    FRESH,
    STALE,
    UNKNOWN,
}

internal data class DeviceIdentity(
    val nodeId: String,
    val deviceKey: String,
)

internal data class FreshnessPresentation(
    val level: FreshnessLevel,
    val label: String,
    val ageLabel: String?,
)

internal data class LatestSnapshotPresentationState(
    val partial: Boolean,
    val errors: List<NodeApiError>,
    val errorsTruncated: Boolean,
    val freshnessByDevice: Map<DeviceIdentity, FreshnessPresentation>,
)

internal fun LatestSnapshotsPayload.toPresentationState(): LatestSnapshotPresentationState {
    val freshness = buildMap {
        nodes.forEach { node ->
            val statesByDevice = node.deviceStates.associateBy(DeviceState::deviceKey)
            node.devices.forEach { device ->
                put(
                    DeviceIdentity(node.nodeId, device.deviceKey),
                    statesByDevice[device.deviceKey].toPresentation(),
                )
            }
        }
    }
    return LatestSnapshotPresentationState(
        partial = partial,
        errors = errors,
        errorsTruncated = errorsTruncated,
        freshnessByDevice = freshness,
    )
}

internal fun DeviceState?.toPresentation(): FreshnessPresentation {
    val level = when {
        this?.lastReceivedAt == null -> FreshnessLevel.UNKNOWN
        stale -> FreshnessLevel.STALE
        else -> FreshnessLevel.FRESH
    }
    return FreshnessPresentation(
        level = level,
        label = when (level) {
            FreshnessLevel.FRESH -> "Fresh"
            FreshnessLevel.STALE -> "Stale"
            FreshnessLevel.UNKNOWN -> "Freshness unknown"
        },
        ageLabel = this?.ageMs?.let(::formatSnapshotAge),
    )
}

internal fun formatSnapshotAge(ageMs: Long): String {
    val clampedAgeMs = ageMs.coerceAtLeast(0)
    val seconds = clampedAgeMs / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        seconds < 1 -> "Just now"
        minutes < 1 -> "${seconds}s ago"
        hours < 1 -> "${minutes}m ago"
        days < 1 -> "${hours}h ago"
        else -> "${days}d ago"
    }
}
