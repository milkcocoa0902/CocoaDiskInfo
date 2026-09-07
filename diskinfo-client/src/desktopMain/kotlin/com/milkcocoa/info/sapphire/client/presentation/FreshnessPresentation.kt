package com.milkcocoa.info.sapphire.client.presentation

import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError

/** UI severity derived from the server's cached/received state metadata. */
internal enum class FreshnessLevel {
    FRESH,
    STALE,
    UNKNOWN,
}

/** Composite key because a device key is not globally unique across nodes. */
internal data class DeviceIdentity(
    val nodeId: String,
    val deviceKey: String,
)

/** Labels and severity used to display one device's cache freshness. */
internal data class FreshnessPresentation(
    val level: FreshnessLevel,
    val label: String,
    val ageLabel: String?,
)

/** Non-domain state derived from a latest payload for dashboard messaging and row labels. */
internal data class LatestSnapshotPresentationState(
    val partial: Boolean,
    val errors: List<NodeApiError>,
    val errorsTruncated: Boolean,
    val freshnessByDevice: Map<DeviceIdentity, FreshnessPresentation>,
)

/**
 * Projects node/device state into UI metadata without changing the raw snapshot payload.
 *
 * Device freshness is looked up by `(nodeId, deviceKey)`; absent state is unknown, not fresh.
 */
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

/** Maps server state to user-facing freshness; a missing receive time remains unknown. */
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

/** Formats a potentially negative server age into coarse, non-negative relative time. */
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
