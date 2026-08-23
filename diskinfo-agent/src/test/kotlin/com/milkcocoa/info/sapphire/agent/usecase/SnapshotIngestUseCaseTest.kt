package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertResult
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertStatus
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SnapshotIngestUseCaseTest {
    @Test
    fun `ingest maps command and owns one read write transaction`() = runBlocking {
        val repository = RecordingIngestRepository()
        val transactionRunner = RecordingIngestTransactionRunner()
        val useCase = TransactionalSnapshotIngestUseCase(repository, transactionRunner)
        val command = IngestSnapshotCommand(
            ingestId = testNodeId(1),
            origin = SnapshotOrigin(testNodeId(2), "node-a"),
            snapshot = testDiskSnapshot("device-a", timestampMillis = 1_000),
            receivedAt = Instant.parse("2026-08-15T01:02:03Z"),
        )

        val result = useCase.ingest(command)

        assertEquals(SnapshotInsertStatus.STORED, result.status)
        assertEquals(command.ingestId, repository.record?.ingestId)
        assertEquals(command.origin, repository.record?.origin)
        assertEquals(command.snapshot, repository.record?.snapshot)
        assertEquals(command.receivedAt, repository.record?.receivedAt)
        assertEquals(1, transactionRunner.readWriteCalls)
    }
}

private class RecordingIngestTransactionRunner : TransactionRunner {
    var readWriteCalls = 0

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()

    override suspend fun <T> readWrite(block: suspend () -> T): T {
        readWriteCalls += 1
        return block()
    }
}

@OptIn(ExperimentalUuidApi::class)
private class RecordingIngestRepository : DiskSnapshotRepository {
    var record: SnapshotPersistenceRecord? = null

    override fun insert(record: SnapshotPersistenceRecord): SnapshotInsertResult {
        this.record = record
        return SnapshotInsertResult(
            status = SnapshotInsertStatus.STORED,
            snapshotId = testNodeId(3),
            ingestId = record.ingestId,
            receivedAt = record.receivedAt,
        )
    }

    override fun findLatestNodes(): List<RawNodeSnapshot> = emptyList()

    override fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = null

    override fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? = null

    override fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage =
        LatestSnapshotPage(rows = emptyList(), hasMore = false, nextCursor = null)

    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory = RawNodeDeviceHistory(
        nodeId = nodeId.toString(),
        nodeName = "",
        deviceKey = deviceKey,
        snapshots = emptyList(),
    )
}
