package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Persistence boundary for immutable raw disk observations.
 *
 * Implementations are called inside the caller's storage transaction. This interface does not
 * evaluate health policy or apply retention; snapshots remain the source data for those later
 * projections. Implementations must preserve ingest idempotency and must keep history reads
 * bounded by the supplied query.
 */
interface DiskSnapshotRepository {
    /** Stores one observation, returning [SnapshotInsertStatus.DUPLICATE] for a replay. */
    fun insert(record: SnapshotPersistenceRecord): SnapshotInsertResult

    /** Returns one latest snapshot per node/device pair, grouped by node for display. */
    fun findLatestNodes(): List<RawNodeSnapshot>

    /** Finds the newest observation for a device key, irrespective of node. */
    fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot?

    @OptIn(ExperimentalUuidApi::class)
    /** Finds the newest observation for a specific stable node/device identity. */
    fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot?

    /** Returns one latest row per active node/device pair using a stable keyset cursor. */
    fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage

    @OptIn(ExperimentalUuidApi::class)
    /** Reads bounded raw history for one node/device pair in the requested time/order window. */
    fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory
}
