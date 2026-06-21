package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.insertDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ExposedDiskSnapshotRepositoryTest {
    private val deviceKeyA = "b25b5b07-5629-5c33-89ba-1ef17c03cc0c"
    private val deviceKeyB = "36a2c1b1-8b7e-5f99-8ad9-bc6ecd2662f2"

    @Test
    fun `insert persists snapshot and findLatestByDeviceKey returns newest snapshot`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()

        val latest = transaction {
            repository.insert(testDiskSnapshot(deviceKeyA, timestampMillis = 1_000, temperatureCelsius = 31))
            repository.insert(testDiskSnapshot(deviceKeyA, timestampMillis = 2_000, temperatureCelsius = 32))
            repository.findLatestByDeviceKey(deviceKeyA)
        }

        assertEquals(2_000L, latest?.timestamp?.toEpochMilliseconds())
        assertEquals(32, latest?.temperatureCelsius)
        assertEquals(deviceKeyA, latest?.deviceKey)
    }

    @Test
    fun `findLatestNodes returns latest snapshot per node and device`() {
        connectDiskSnapshotTestDatabase()
        val nodeA = testNodeId(1)
        val nodeB = testNodeId(2)

        insertDiskSnapshot(nodeA, "node-a", testDiskSnapshot(deviceKeyA, timestampMillis = 1_000))
        insertDiskSnapshot(nodeA, "node-a", testDiskSnapshot(deviceKeyA, timestampMillis = 2_000))
        insertDiskSnapshot(nodeA, "node-a", testDiskSnapshot(deviceKeyB, timestampMillis = 3_000))
        insertDiskSnapshot(nodeB, "node-b", testDiskSnapshot(deviceKeyA, timestampMillis = 4_000))

        val nodes = transaction {
            ExposedDiskSnapshotRepository().findLatestNodes()
        }

        assertEquals(listOf("node-a", "node-b"), nodes.map { it.nodeName })
        assertEquals(listOf(deviceKeyB, deviceKeyA), nodes[0].devices.map { it.deviceKey })
        assertEquals(listOf(3_000L, 2_000L), nodes[0].devices.map { it.timestamp.toEpochMilliseconds() })
        assertEquals(listOf(deviceKeyA), nodes[1].devices.map { it.deviceKey })
        assertEquals(listOf(4_000L), nodes[1].devices.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `findLatestByDeviceKey returns null for unknown device`() {
        connectDiskSnapshotTestDatabase()

        val latest = transaction {
            ExposedDiskSnapshotRepository().findLatestByDeviceKey("missing")
        }

        assertEquals(null, latest)
    }

    @Test
    fun `findHistory returns bounded snapshots for selected node and device in descending order`() {
        connectDiskSnapshotTestDatabase()
        val selectedNodeId = testNodeId(1)
        val otherNodeId = testNodeId(2)
        val deviceKey = deviceKeyA

        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 1_000))
        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 2_000))
        insertDiskSnapshot(selectedNodeId, "node-a", testDiskSnapshot(deviceKeyB, timestampMillis = 3_000))
        insertDiskSnapshot(otherNodeId, "node-b", testDiskSnapshot(deviceKey, timestampMillis = 4_000))

        val payload = transaction {
            ExposedDiskSnapshotRepository().findHistory(
                nodeId = selectedNodeId,
                deviceKey = deviceKey,
                query = HistoryQuery(limit = 10),
            )
        }

        assertEquals(selectedNodeId.toString(), payload.nodeId)
        assertEquals("node-a", payload.nodeName)
        assertEquals(deviceKey, payload.deviceKey)
        assertEquals(listOf(2_000L, 1_000L), payload.snapshots.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `findHistory applies asc order limit and inclusive time range`() {
        connectDiskSnapshotTestDatabase()
        val nodeId = testNodeId(1)
        val deviceKey = deviceKeyA

        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 1_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 2_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 3_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 4_000))

        val payload = transaction {
            ExposedDiskSnapshotRepository().findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = HistoryQuery(
                    limit = 2,
                    from = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(2_000), ZoneOffset.UTC),
                    to = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(4_000), ZoneOffset.UTC),
                    order = HistoryOrder.ASC,
                ),
            )
        }

        assertEquals(listOf(2_000L, 3_000L), payload.snapshots.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `findHistory returns empty payload for unknown node and device`() {
        connectDiskSnapshotTestDatabase()
        val payload = transaction {
            ExposedDiskSnapshotRepository().findHistory(
                nodeId = testNodeId(1),
                deviceKey = "missing",
                query = HistoryQuery(),
            )
        }

        assertEquals(testNodeId(1).toString(), payload.nodeId)
        assertEquals("", payload.nodeName)
        assertEquals("missing", payload.deviceKey)
        assertEquals(emptyList(), payload.snapshots)
    }
}
