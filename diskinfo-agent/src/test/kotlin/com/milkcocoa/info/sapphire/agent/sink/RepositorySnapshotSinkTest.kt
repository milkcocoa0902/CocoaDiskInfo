package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RepositorySnapshotSinkTest {
    private val snapshot = testDiskSnapshot("sink-test-device", timestampMillis = 1_000)

    @Test
    fun `write persists snapshot`() = runBlocking {
        val savedSnapshots = mutableListOf<DiskSnapshot>()
        val sink = RepositorySnapshotSink(
            SaveOnlySnapshotUseCase { savedSnapshots += it },
        )

        sink.write(snapshot)

        assertEquals(listOf(snapshot), savedSnapshots)
    }

    @Test
    fun `write reports ordinary persistence failure without propagating it`() = runBlocking {
        val failure = IllegalStateException("database unavailable")
        var saveCalls = 0
        val sink = RepositorySnapshotSink(
            SaveOnlySnapshotUseCase {
                saveCalls += 1
                throw failure
            },
        )

        sink.write(snapshot)

        assertEquals(1, saveCalls)
    }

    @Test
    fun `write propagates cancellation`() = runBlocking {
        val cancellation = CancellationException("stopping")
        val sink = RepositorySnapshotSink(
            SaveOnlySnapshotUseCase { throw cancellation },
        )

        val thrown = assertFailsWith<CancellationException> {
            sink.write(snapshot)
        }

        assertSame(cancellation, thrown)
    }
}

@OptIn(ExperimentalUuidApi::class)
private class SaveOnlySnapshotUseCase(
    private val save: suspend (DiskSnapshot) -> Unit,
) : SnapshotUseCase {
    override suspend fun saveSnapshot(snapshot: DiskSnapshot) = save(snapshot)

    override suspend fun findLatestNodes(): List<NodeSnapshot> = error("Not used by RepositorySnapshotSink.")

    override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? =
        error("Not used by RepositorySnapshotSink.")

    override suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload = error("Not used by RepositorySnapshotSink.")
}
