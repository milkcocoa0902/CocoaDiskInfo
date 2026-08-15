package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.insertDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testSnapshotRecord
import com.milkcocoa.info.sapphire.agent.testNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.ExperimentalUuidApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile

@OptIn(ExperimentalUuidApi::class)
class ExposedDiskSnapshotRepositoryTest {
    private val deviceKeyA = "b25b5b07-5629-5c33-89ba-1ef17c03cc0c"
    private val deviceKeyB = "36a2c1b1-8b7e-5f99-8ad9-bc6ecd2662f2"

    @Test
    fun `insert persists snapshot and findLatestByDeviceKey returns newest snapshot`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()

        val latest = transaction {
            repository.insert(testSnapshotRecord(testDiskSnapshot(deviceKeyA, timestampMillis = 1_000, temperatureCelsius = 31)))
            repository.insert(testSnapshotRecord(testDiskSnapshot(deviceKeyA, timestampMillis = 2_000, temperatureCelsius = 32)))
            repository.findLatestByDeviceKey(deviceKeyA)
        }

        assertEquals(2_000L, latest?.timestamp?.toEpochMilliseconds())
        assertEquals(32, latest?.temperatureCelsius)
        assertEquals(deviceKeyA, latest?.deviceKey)
    }

    @Test
    fun `insert stores collect time with canonical UTC offset`() {
        val jdbcUrl = connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()

        transaction {
            repository.insert(testSnapshotRecord(testDiskSnapshot(deviceKeyA, timestampMillis = 1_000)))
        }

        val storedCollectTime = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT collect_time FROM disk_snapshot").use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }
        assertEquals(
            ZoneOffset.UTC,
            OffsetDateTime.parse(storedCollectTime.replace(' ', 'T')).offset,
        )
    }

    @Test
    fun `insert is idempotent per node and rejects a different snapshot`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()
        val nodeId = testNodeId(11)
        val ingestId = testNodeId(21)
        val receivedAt = Instant.parse("2026-08-15T01:02:03Z")
        val snapshot = testDiskSnapshot(deviceKeyA, timestampMillis = 1_000)
        val record = testSnapshotRecord(
            snapshot = snapshot,
            nodeId = nodeId,
            nodeName = "node-a",
            ingestId = ingestId,
            receivedAt = receivedAt,
        )

        val (stored, duplicate) = transaction {
            repository.insert(record) to repository.insert(
                record.copy(
                    origin = record.origin.copy(nodeName = "renamed-node"),
                    receivedAt = receivedAt.plusSeconds(30),
                ),
            )
        }

        assertEquals(SnapshotInsertStatus.STORED, stored.status)
        assertEquals(SnapshotInsertStatus.DUPLICATE, duplicate.status)
        assertEquals(stored.snapshotId, duplicate.snapshotId)
        assertEquals(receivedAt, duplicate.receivedAt)

        assertFailsWith<IngestIdConflictException> {
            transaction {
                repository.insert(
                    record.copy(snapshot = snapshot.copy(temperatureCelsius = 99)),
                )
            }
        }
    }

    @Test
    fun `same ingest id is independent across nodes and latest is node scoped`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()
        val ingestId = testNodeId(31)
        val nodeA = testNodeId(41)
        val nodeB = testNodeId(42)
        val snapshotA = testDiskSnapshot(deviceKeyA, timestampMillis = 1_000, temperatureCelsius = 31)
        val snapshotB = testDiskSnapshot(deviceKeyA, timestampMillis = 2_000, temperatureCelsius = 32)

        val results = transaction {
            listOf(
                repository.insert(testSnapshotRecord(snapshotA, nodeA, "node-a", ingestId)),
                repository.insert(testSnapshotRecord(snapshotB, nodeB, "node-b", ingestId)),
            )
        }
        val latest = transaction {
            repository.findLatest(nodeA, deviceKeyA) to repository.findLatest(nodeB, deviceKeyA)
        }

        assertEquals(listOf(SnapshotInsertStatus.STORED, SnapshotInsertStatus.STORED), results.map { it.status })
        assertEquals(31, latest.first?.snapshot?.temperatureCelsius)
        assertEquals(32, latest.second?.snapshot?.temperatureCelsius)
    }

    @Test
    fun `concurrent retries converge to one stored row`() = runBlocking {
        val storage = StorageSettings.fromJdbcUrl(
            "jdbc:sqlite:${createTempFile().absolutePathString()}",
        )
        createStorageMigratorFactory().create(storage).migrate(storage)
        StorageConnectionFactory.connect(storage).use { connection ->
            val runner = ExposedTransactionRunner(connection.database)
            val repository = ExposedDiskSnapshotRepository()
            val start = CompletableDeferred<Unit>()
            val record = testSnapshotRecord(
                snapshot = testDiskSnapshot(deviceKeyA, timestampMillis = 1_000),
                nodeId = testNodeId(51),
                nodeName = "node-a",
                ingestId = testNodeId(52),
            )

            val results = List(8) {
                async(Dispatchers.IO) {
                    start.await()
                    runner.readWrite { repository.insert(record) }
                }
            }
            start.complete(Unit)

            assertEquals(
                1,
                results.awaitAll().count { it.status == SnapshotInsertStatus.STORED },
            )
            assertEquals(
                1,
                runner.readOnly { DiskSnapshotTable.selectAll().count() },
            )
        }
    }

    @Test
    fun `latest page is device bounded keyset ordered and excludes disabled nodes`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()
        val registry = ExposedNodeAgentRegistryRepository()
        val nodeA = testNodeId(61)
        val nodeB = testNodeId(62)
        val disabledNode = testNodeId(63)
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")

        transaction {
            registry.activate(NodeAgentRegistration(nodeA, "node-a", 60, joinedAt))
            registry.activate(NodeAgentRegistration(nodeB, "node-b", 60, joinedAt))
            registry.activate(NodeAgentRegistration(disabledNode, "node-c", 60, joinedAt))
            registry.disable(disabledNode)

            listOf(
                Triple(nodeA, "device-a", 1_000L),
                Triple(nodeA, "device-a", 2_000L),
                Triple(nodeA, "device-b", 3_000L),
                Triple(nodeA, "device-c", 4_000L),
                Triple(nodeB, "device-a", 5_000L),
                Triple(nodeB, "device-b", 6_000L),
                Triple(disabledNode, "device-a", 7_000L),
            ).forEachIndexed { index, (nodeId, deviceKey, timestamp) ->
                repository.insert(
                    testSnapshotRecord(
                        snapshot = testDiskSnapshot(deviceKey, timestampMillis = timestamp),
                        nodeId = nodeId,
                        nodeName = "historical-node-$nodeId",
                        ingestId = testNodeId(100L + index),
                    ),
                )
            }
        }

        val firstPage = transaction {
            repository.findLatestPage(LatestSnapshotPageRequest(limit = 2))
        }
        val secondPage = transaction {
            repository.findLatestPage(
                LatestSnapshotPageRequest(cursor = firstPage.nextCursor, limit = 2),
            )
        }
        val thirdPage = transaction {
            repository.findLatestPage(
                LatestSnapshotPageRequest(cursor = secondPage.nextCursor, limit = 2),
            )
        }

        assertEquals(
            listOf(nodeA to "device-a", nodeA to "device-b"),
            firstPage.rows.map { it.origin.nodeId to it.snapshot.deviceKey },
        )
        assertEquals(listOf(2_000L, 3_000L), firstPage.rows.map { it.snapshot.timestamp.toEpochMilliseconds() })
        assertEquals(true, firstPage.hasMore)
        assertEquals(LatestSnapshotCursor(nodeA, "device-b"), firstPage.nextCursor)
        assertEquals(
            listOf(nodeA to "device-c", nodeB to "device-a"),
            secondPage.rows.map { it.origin.nodeId to it.snapshot.deviceKey },
        )
        assertEquals(true, secondPage.hasMore)
        assertEquals(listOf(nodeB to "device-b"), thirdPage.rows.map {
            it.origin.nodeId to it.snapshot.deviceKey
        })
        assertEquals(false, thirdPage.hasMore)
        assertEquals(null, thirdPage.nextCursor)
    }

    @Test
    fun `latest page uses snapshot id as a stable tie breaker`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedDiskSnapshotRepository()
        val registry = ExposedNodeAgentRegistryRepository()
        val nodeId = testNodeId(71)
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")
        val timestamp = 1_000L

        val inserted = transaction {
            registry.activate(NodeAgentRegistration(nodeId, "node-a", 60, joinedAt))
            listOf(
                repository.insert(
                    testSnapshotRecord(
                        testDiskSnapshot(deviceKeyA, timestamp, temperatureCelsius = 31),
                        nodeId = nodeId,
                        nodeName = "node-a",
                    ),
                ),
                repository.insert(
                    testSnapshotRecord(
                        testDiskSnapshot(deviceKeyA, timestamp, temperatureCelsius = 32),
                        nodeId = nodeId,
                        nodeName = "node-a",
                    ),
                ),
            )
        }
        val expectedTemperature = if (inserted[0].snapshotId > inserted[1].snapshotId) 31 else 32

        val page = transaction {
            repository.findLatestPage(LatestSnapshotPageRequest(limit = 1))
        }

        assertEquals(1, page.rows.size)
        assertEquals(expectedTemperature, page.rows.single().snapshot.temperatureCelsius)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun `latest page validates device row bounds`() {
        assertFailsWith<IllegalArgumentException> { LatestSnapshotPageRequest(limit = 0) }
        assertFailsWith<IllegalArgumentException> {
            LatestSnapshotPageRequest(limit = LatestSnapshotPageRequest.MAX_LIMIT + 1)
        }
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
