package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.insertDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class DiskSnapshotRepositoryTest {
    @Test
    fun `findHistory returns bounded snapshots for selected node and device in descending order`() {
        connectDiskSnapshotTestDatabase()
        val selectedNodeId = testNodeId(1)
        val otherNodeId = testNodeId(2)
        val deviceKey = "serial-a"

        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 1_000))
        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 2_000))
        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot("serial-b", timestampMillis = 3_000))
        insertDiskSnapshot(otherNodeId, "node-b", testDiskSnapshot(deviceKey, timestampMillis = 4_000))

        val payload = DiskSnapshotRepository().findHistory(
            nodeId = selectedNodeId,
            deviceKey = deviceKey,
            query = HistoryQuery(limit = 10),
        )

        assertEquals(selectedNodeId.toString(), payload.nodeId)
        assertEquals("node-a", payload.nodeName)
        assertEquals(deviceKey, payload.deviceKey)
        assertEquals(listOf(2_000L, 1_000L), payload.snapshots.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `findHistory applies asc order limit and inclusive time range`() {
        connectDiskSnapshotTestDatabase()
        val nodeId = testNodeId(1)
        val deviceKey = "serial-a"

        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 1_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 2_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 3_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 4_000))

        val payload = DiskSnapshotRepository().findHistory(
            nodeId = nodeId,
            deviceKey = deviceKey,
            query = HistoryQuery(
                limit = 2,
                from = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(2_000), ZoneOffset.UTC),
                to = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(4_000), ZoneOffset.UTC),
                order = HistoryOrder.ASC,
            ),
        )

        assertEquals(listOf(2_000L, 3_000L), payload.snapshots.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `findHistory returns empty payload for unknown node and device`() {
        connectDiskSnapshotTestDatabase()
        val payload = DiskSnapshotRepository().findHistory(
            nodeId = testNodeId(1),
            deviceKey = "missing",
            query = HistoryQuery(),
        )

        assertEquals(testNodeId(1).toString(), payload.nodeId)
        assertEquals("", payload.nodeName)
        assertEquals("missing", payload.deviceKey)
        assertEquals(emptyList(), payload.snapshots)
    }
}
