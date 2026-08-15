package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.NodeIdentity
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface SnapshotUseCase {
    suspend fun saveSnapshot(snapshot: DiskSnapshot)

    suspend fun findLatestNodes(): List<NodeSnapshot>

    suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot?

    @OptIn(ExperimentalUuidApi::class)
    suspend fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? =
        error("Node-scoped latest lookup is not implemented.")

    suspend fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage =
        error("Paged latest lookup is not implemented.")

    @OptIn(ExperimentalUuidApi::class)
    suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload
}

@OptIn(ExperimentalUuidApi::class)
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

    override suspend fun findLatestNodes(): List<NodeSnapshot> =
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
    ): NodeDeviceHistoryPayload =
        transactionRunner.readOnly {
            repository.findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = query,
            )
        }
}

fun interface SnapshotOriginProvider {
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
fun interface IngestIdProvider {
    fun create(): Uuid
}
