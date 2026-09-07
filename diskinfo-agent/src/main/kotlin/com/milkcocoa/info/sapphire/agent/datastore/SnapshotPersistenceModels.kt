package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
/** Stable node metadata stored alongside an observation; [nodeName] is display context. */
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
/**
 * Ingest DTO crossing into storage.
 *
 * [ingestId] is scoped to [origin].nodeId and is the idempotency key; [snapshot] is the immutable
 * observed payload, while [receivedAt] is the supplied receipt time for this storage boundary.
 */
data class SnapshotPersistenceRecord(
    val ingestId: Uuid,
    val origin: SnapshotOrigin,
    val snapshot: DiskSnapshot,
    val receivedAt: Instant,
)

/** Whether an ingest created a row or matched the existing same-payload row. */
enum class SnapshotInsertStatus {
    STORED,
    DUPLICATE,
}

@OptIn(ExperimentalUuidApi::class)
/** Result of an idempotent insert, including the persistent row and ingest/receipt metadata. */
data class SnapshotInsertResult(
    val status: SnapshotInsertStatus,
    val snapshotId: Uuid,
    val ingestId: Uuid,
    val receivedAt: Instant,
)

@OptIn(ExperimentalUuidApi::class)
/** Snapshot plus persistence metadata needed by APIs and cursor pagination. */
data class StoredDiskSnapshot(
    val snapshotId: Uuid,
    val ingestId: Uuid,
    val origin: SnapshotOrigin,
    val snapshot: DiskSnapshot,
    val receivedAt: Instant,
)

/** Raw latest-node repository result; policy-derived fields never cross this boundary. */
data class RawNodeSnapshot(
    val nodeId: String,
    val nodeName: String,
    val devices: List<DiskSnapshot>,
)

/** Raw repository history result; policy-derived fields never cross this boundary. */
data class RawNodeDeviceHistory(
    val nodeId: String,
    val nodeName: String,
    val deviceKey: String,
    val snapshots: List<DiskSnapshot>,
)

@OptIn(ExperimentalUuidApi::class)
/** Raised when one node reuses an ingest id for a different immutable snapshot payload. */
class IngestIdConflictException(
    val nodeId: Uuid,
    val ingestId: Uuid,
) : IllegalStateException("ingestId $ingestId is already used by node $nodeId for a different snapshot.")

@OptIn(ExperimentalUuidApi::class)
/**
 * Opaque keyset position for latest-page reads, ordered by node id then device key.
 *
 * It is a position rather than a timestamp: new observations do not invalidate the ordering of
 * already returned node/device keys, although callers should not treat it as a history cursor.
 */
data class LatestSnapshotCursor(
    val nodeId: Uuid,
    val deviceKey: String,
) {
    init {
        require(deviceKey.isNotBlank()) { "cursor deviceKey must not be blank." }
    }
}

/** Request for one bounded latest snapshot page. */
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

/** Page rows and continuation state for a latest snapshot keyset query. */
data class LatestSnapshotPage(
    val rows: List<StoredDiskSnapshot>,
    val hasMore: Boolean,
    val nextCursor: LatestSnapshotCursor?,
)
