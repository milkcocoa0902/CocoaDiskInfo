package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.NodeIdentity
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Application boundary for local snapshot persistence and bounded latest/history queries.
 * Implementations must keep repository work inside the appropriate transaction runner and
 * return raw observations; API adapters apply the active health policy when rendering them.
 */
interface SnapshotUseCase {
    /** Persists [snapshot] with a generated ingest id and local origin. */
    suspend fun saveSnapshot(snapshot: DiskSnapshot)

    /** Returns one raw latest row per known origin/node. */
    suspend fun findLatestNodes(): List<RawNodeSnapshot>

    /** Returns the latest raw snapshot for the stable [deviceKey], or null when absent. */
    suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot?

    /**
     * Returns a node-scoped latest row, or null when no row exists for the node/device pair.
     * Implementations that do not support this operation throw [IllegalStateException].
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? =
        error("Node-scoped latest lookup is not implemented.")

    /**
     * Returns one bounded latest page and an opaque repository cursor when more rows exist.
     * Implementations that do not support this operation throw [IllegalStateException].
     */
    suspend fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage =
        error("Paged latest lookup is not implemented.")

    @OptIn(ExperimentalUuidApi::class)
    /** Returns bounded raw history for the node/device pair in the requested time/order window. */
    suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory
}

@OptIn(ExperimentalUuidApi::class)
/** Snapshot use case whose every repository operation runs in a read-only/read-write transaction. */
class TransactionalSnapshotUseCase(
    private val repository: DiskSnapshotRepository,
    private val transactionRunner: TransactionRunner,
    private val localOriginProvider: SnapshotOriginProvider = LocalSnapshotOriginProvider,
    private val ingestIdProvider: IngestIdProvider = IngestIdProvider { Uuid.random() },
    private val clock: Clock = Clock.systemUTC(),
) : SnapshotUseCase {
    override suspend fun saveSnapshot(snapshot: DiskSnapshot) {
        transactionRunner.readWrite {
            repository.insert(
                SnapshotPersistenceRecord(
                    ingestId = ingestIdProvider.create(),
                    origin = localOriginProvider.get(),
                    snapshot = snapshot,
                    receivedAt = clock.instant(),
                ),
            )
        }
    }

    override suspend fun findLatestNodes(): List<RawNodeSnapshot> =
        transactionRunner.readOnly {
            repository.findLatestNodes()
        }

    override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? =
        transactionRunner.readOnly {
            repository.findLatestByDeviceKey(deviceKey)
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? =
        transactionRunner.readOnly {
            repository.findLatest(nodeId, deviceKey)
        }

    override suspend fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage =
        transactionRunner.readOnly {
            repository.findLatestPage(request)
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory =
        transactionRunner.readOnly {
            repository.findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = query,
            )
        }
}

/** Supplies the origin attached to locally collected snapshots. */
fun interface SnapshotOriginProvider {
    /** Returns the current node identity and display name. */
    fun get(): SnapshotOrigin
}

private object LocalSnapshotOriginProvider : SnapshotOriginProvider {
    @OptIn(ExperimentalUuidApi::class)
    override fun get(): SnapshotOrigin = SnapshotOrigin(
        nodeId = NodeIdentity.nodeId,
        nodeName = NodeIdentity.nodeName,
    )
}

@OptIn(ExperimentalUuidApi::class)
/** Generates the idempotency identity attached to locally persisted snapshots. */
fun interface IngestIdProvider {
    /** Returns a new ingest UUID. */
    fun create(): Uuid
}
