package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.coroutines.runBlocking
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
        val useCase = TransactionalSnapshotUseCase(repository, transactionRunner)
        val snapshot = testDiskSnapshot(deviceKey, timestampMillis = 1_000)

        useCase.saveSnapshot(snapshot)

        assertEquals(listOf("insert:$deviceKey"), repository.calls)
        assertEquals(0, transactionRunner.readOnlyCalls)
        assertEquals(1, transactionRunner.readWriteCalls)
    }

    @Test
    fun `query methods run repository calls in read only transactions`() = runBlocking {
        val nodeId = testNodeId(1)
        val snapshot = testDiskSnapshot(deviceKey, timestampMillis = 1_000)
        val repository = RecordingDiskSnapshotRepository(
            latestNodesResult = listOf(NodeSnapshot(nodeId.toString(), "node-a", listOf(snapshot))),
            latestByDeviceKeyResult = snapshot,
            historyResult = NodeDeviceHistoryPayload(
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
        val history = useCase.findHistory(nodeId, deviceKey, HistoryQuery(limit = 5))

        assertEquals(listOf("node-a"), latestNodes.map { it.nodeName })
        assertEquals(deviceKey, latestSnapshot?.deviceKey)
        assertEquals(listOf(deviceKey), history.snapshots.map { it.deviceKey })
        assertEquals(
            listOf(
                "findLatestNodes",
                "findLatestByDeviceKey:$deviceKey",
                "findHistory:$nodeId:$deviceKey:5",
            ),
            repository.calls,
        )
        assertEquals(3, transactionRunner.readOnlyCalls)
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
    private val latestNodesResult: List<NodeSnapshot> = emptyList(),
    private val latestByDeviceKeyResult: DiskSnapshot? = null,
    private val historyResult: NodeDeviceHistoryPayload = NodeDeviceHistoryPayload(
        nodeId = "",
        nodeName = "",
        deviceKey = "",
        snapshots = emptyList(),
    ),
) : DiskSnapshotRepository {
    val calls = mutableListOf<String>()

    override fun insert(snapshot: DiskSnapshot) {
        calls += "insert:${snapshot.deviceKey}"
    }

    override fun findLatestNodes(): List<NodeSnapshot> {
        calls += "findLatestNodes"
        return latestNodesResult
    }

    override fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? {
        calls += "findLatestByDeviceKey:$deviceKey"
        return latestByDeviceKeyResult
    }

    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload {
        calls += "findHistory:$nodeId:$deviceKey:${query.limit}"
        return historyResult
    }
}
