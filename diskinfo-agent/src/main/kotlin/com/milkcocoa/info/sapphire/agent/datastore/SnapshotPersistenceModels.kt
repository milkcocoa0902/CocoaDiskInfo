package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class SnapshotOrigin(
    val nodeId: Uuid,
    val nodeName: String,
) {
    init {
        require(nodeName.isNotBlank()) { "nodeName must not be blank." }
        require(nodeName.length <= 255) { "nodeName must be at most 255 characters." }
    }
}

@OptIn(ExperimentalUuidApi::class)
data class SnapshotPersistenceRecord(
    val ingestId: Uuid,
    val origin: SnapshotOrigin,
    val snapshot: DiskSnapshot,
    val receivedAt: Instant,
)

enum class SnapshotInsertStatus {
    STORED,
    DUPLICATE,
}

@OptIn(ExperimentalUuidApi::class)
data class SnapshotInsertResult(
    val status: SnapshotInsertStatus,
    val snapshotId: Uuid,
    val ingestId: Uuid,
    val receivedAt: Instant,
)

@OptIn(ExperimentalUuidApi::class)
data class StoredDiskSnapshot(
    val snapshotId: Uuid,
    val ingestId: Uuid,
    val origin: SnapshotOrigin,
    val snapshot: DiskSnapshot,
    val receivedAt: Instant,
)

/** Raw repository result. Policy-derived fields never cross this boundary. */
data class RawNodeSnapshot(
    val nodeId: String,
    val nodeName: String,
    val devices: List<DiskSnapshot>,
)

/** Raw repository history result. Policy-derived fields never cross this boundary. */
data class RawNodeDeviceHistory(
    val nodeId: String,
    val nodeName: String,
    val deviceKey: String,
    val snapshots: List<DiskSnapshot>,
)

@OptIn(ExperimentalUuidApi::class)
class IngestIdConflictException(
    val nodeId: Uuid,
    val ingestId: Uuid,
) : IllegalStateException("ingestId $ingestId is already used by node $nodeId for a different snapshot.")

@OptIn(ExperimentalUuidApi::class)
data class LatestSnapshotCursor(
    val nodeId: Uuid,
    val deviceKey: String,
) {
    init {
        require(deviceKey.isNotBlank()) { "cursor deviceKey must not be blank." }
    }
}

data class LatestSnapshotPageRequest(
    val cursor: LatestSnapshotCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}

data class LatestSnapshotPage(
    val rows: List<StoredDiskSnapshot>,
    val hasMore: Boolean,
    val nextCursor: LatestSnapshotCursor?,
)
