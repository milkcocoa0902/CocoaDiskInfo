package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertResult
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertStatus
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SnapshotUseCaseTest {
    private val deviceKey = "b25b5b07-5629-5c33-89ba-1ef17c03cc0c"

    @Test
    fun `saveSnapshot runs repository insert in read write transaction`() = runBlocking {
        val repository = RecordingDiskSnapshotRepository()
        val transactionRunner = RecordingTransactionRunner()
        val origin = SnapshotOrigin(testNodeId(1), "local-node")
        val ingestId = testNodeId(2)
        val receivedAt = Instant.parse("2026-08-15T01:02:03Z")
        val useCase = TransactionalSnapshotUseCase(
            repository = repository,
            transactionRunner = transactionRunner,
            localOriginProvider = SnapshotOriginProvider { origin },
            ingestIdProvider = IngestIdProvider { ingestId },
            clock = Clock.fixed(receivedAt, ZoneOffset.UTC),
        )
        val snapshot = testDiskSnapshot(deviceKey, timestampMillis = 1_000)

        useCase.saveSnapshot(snapshot)

        assertEquals(listOf("insert:$deviceKey"), repository.calls)
        assertEquals(origin, repository.lastInsertedRecord?.origin)
        assertEquals(ingestId, repository.lastInsertedRecord?.ingestId)
        assertEquals(receivedAt, repository.lastInsertedRecord?.receivedAt)
        assertEquals(0, transactionRunner.readOnlyCalls)
        assertEquals(1, transactionRunner.readWriteCalls)
    }

    @Test
    fun `query methods run repository calls in read only transactions`() = runBlocking {
        val nodeId = testNodeId(1)
        val snapshot = testDiskSnapshot(deviceKey, timestampMillis = 1_000)
        val repository = RecordingDiskSnapshotRepository(
            latestNodesResult = listOf(RawNodeSnapshot(nodeId.toString(), "node-a", listOf(snapshot))),
            latestByDeviceKeyResult = snapshot,
            historyResult = RawNodeDeviceHistory(
                nodeId = nodeId.toString(),
                nodeName = "node-a",
                deviceKey = deviceKey,
                snapshots = listOf(snapshot),
            ),
        )
        val transactionRunner = RecordingTransactionRunner()
        val useCase = TransactionalSnapshotUseCase(repository, transactionRunner)

        val latestNodes = useCase.findLatestNodes()
        val latestSnapshot = useCase.findLatestByDeviceKey(deviceKey)
        val latestPage = useCase.findLatestPage(LatestSnapshotPageRequest(limit = 5))
        val history = useCase.findHistory(nodeId, deviceKey, HistoryQuery(limit = 5))

        assertEquals(listOf("node-a"), latestNodes.map { it.nodeName })
        assertEquals(deviceKey, latestSnapshot?.deviceKey)
        assertEquals(false, latestPage.hasMore)
        assertEquals(listOf(deviceKey), history.snapshots.map { it.deviceKey })
        assertEquals(
            listOf(
                "findLatestNodes",
                "findLatestByDeviceKey:$deviceKey",
                "findLatestPage:5",
                "findHistory:$nodeId:$deviceKey:5",
            ),
            repository.calls,
        )
        assertEquals(4, transactionRunner.readOnlyCalls)
        assertEquals(0, transactionRunner.readWriteCalls)
    }
}

private class RecordingTransactionRunner : TransactionRunner {
    var readOnlyCalls = 0
        private set
    var readWriteCalls = 0
        private set

    override suspend fun <T> readOnly(block: suspend () -> T): T {
        readOnlyCalls += 1
        return block()
    }

    override suspend fun <T> readWrite(block: suspend () -> T): T {
        readWriteCalls += 1
        return block()
    }
}

@OptIn(ExperimentalUuidApi::class)
private class RecordingDiskSnapshotRepository(
    private val latestNodesResult: List<RawNodeSnapshot> = emptyList(),
    private val latestByDeviceKeyResult: DiskSnapshot? = null,
    private val historyResult: RawNodeDeviceHistory = RawNodeDeviceHistory(
        nodeId = "",
        nodeName = "",
        deviceKey = "",
        snapshots = emptyList(),
    ),
) : DiskSnapshotRepository {
    val calls = mutableListOf<String>()
    var lastInsertedRecord: SnapshotPersistenceRecord? = null

    override fun insert(record: SnapshotPersistenceRecord): SnapshotInsertResult {
        lastInsertedRecord = record
        calls += "insert:${record.snapshot.deviceKey}"
        return SnapshotInsertResult(
            status = SnapshotInsertStatus.STORED,
            snapshotId = testNodeId(99),
            ingestId = record.ingestId,
            receivedAt = record.receivedAt,
        )
    }

    override fun findLatestNodes(): List<RawNodeSnapshot> {
        calls += "findLatestNodes"
        return latestNodesResult
    }

    override fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? {
        calls += "findLatestByDeviceKey:$deviceKey"
        return latestByDeviceKeyResult
    }

    override fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? = null

    override fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage =
        LatestSnapshotPage(rows = emptyList(), hasMore = false, nextCursor = null).also {
            calls += "findLatestPage:${request.limit}"
        }

    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory {
        calls += "findHistory:$nodeId:$deviceKey:${query.limit}"
        return historyResult
    }
}
