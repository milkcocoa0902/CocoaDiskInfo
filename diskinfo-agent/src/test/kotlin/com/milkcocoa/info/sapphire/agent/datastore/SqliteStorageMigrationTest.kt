package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testSnapshotRecord
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.agent.datastore.NodeIdentity.nodeId
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.time.OffsetDateTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class SqliteStorageMigrationTest {
    @Test
    fun `v2 backfills phase 4 identity and receive time without changing payload`() {
        val jdbcUrl = "jdbc:sqlite:${createTempFile().absolutePathString()}"
        val storage = StorageSettings.fromJdbcUrl(jdbcUrl)
        Flyway.configure()
            .dataSource(jdbcUrl, null, null)
            .locations("classpath:db/migration/sqlite")
            .target("1")
            .load()
            .migrate()

        val snapshotId = testNodeId(101)
        val existingNodeId = testNodeId(102)
        val snapshot = testDiskSnapshot("phase4-device", timestampMillis = 1_754_874_123_456L)
        val storedTime = "2025-08-11 01:02:03.456Z"
        DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO disk_snapshot (
                    snapshot_id, node_id, node_name, collect_time, device_key,
                    connection_protocol, device_path, snapshot_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, snapshotId.toByteArray())
                statement.setBytes(2, existingNodeId.toByteArray())
                statement.setString(3, "phase4-node")
                statement.setString(4, storedTime)
                statement.setString(5, snapshot.deviceKey)
                statement.setString(6, snapshot.metricsSnapshot.protocol.name)
                statement.setString(7, snapshot.path)
                statement.setString(8, Json.encodeToString(snapshot))
                statement.executeUpdate()
            }
        }

        createStorageMigratorFactory().create(storage).migrate(storage)

        val migrated = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    """
                    SELECT hex(snapshot_id), hex(ingest_id), collect_time, received_at,
                           node_name, snapshot_json
                    FROM disk_snapshot
                    """.trimIndent(),
                ).use { rows ->
                    assertTrue(rows.next())
                    listOf(
                        rows.getString(1),
                        rows.getString(2),
                        rows.getString(3),
                        rows.getString(4),
                        rows.getString(5),
                        rows.getString(6),
                    )
                }
            }
        }

        assertEquals(migrated[0], migrated[1])
        assertEquals(storedTime, migrated[2])
        assertEquals(storedTime, migrated[3])
        assertEquals("phase4-node", migrated[4])
        assertEquals(snapshot, Json.decodeFromString<DiskSnapshot>(migrated[5]))
    }

    @Test
    fun `latest schema uses UTC Z and preserves strict cleanup and history boundaries`() {
        val jdbcUrl = "jdbc:sqlite:${createTempFile().absolutePathString()}"
        val storage = StorageSettings.fromJdbcUrl(jdbcUrl)
        val migrator = createStorageMigratorFactory().create(storage)
        migrator.migrate(storage)
        migrator.migrate(storage)
        Database.connect(jdbcUrl, "org.sqlite.JDBC")

        val deviceKey = "sqlite-v1-boundary-device"
        val before = OffsetDateTime.parse("2026-07-09T15:59:59.123Z")
        val atCutoff = OffsetDateTime.parse("2026-07-09T16:00:00.456Z")
        val after = OffsetDateTime.parse("2026-07-09T16:00:01.789Z")
        val repository = ExposedDiskSnapshotRepository()
        val maintenanceRepository = ExposedSnapshotMaintenanceRepository()

        transaction {
            repository.insert(
                testSnapshotRecord(
                    testDiskSnapshot(
                        deviceKey = deviceKey,
                        timestampMillis = before.toInstant().toEpochMilli(),
                    ),
                ),
            )
            repository.insert(
                testSnapshotRecord(
                    testDiskSnapshot(
                        deviceKey = deviceKey,
                        timestampMillis = atCutoff.toInstant().toEpochMilli(),
                    ),
                ),
            )
            repository.insert(
                testSnapshotRecord(
                    testDiskSnapshot(
                        deviceKey = deviceKey,
                        timestampMillis = after.toInstant().toEpochMilli(),
                    ),
                ),
            )
        }

        val appliedVersions = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history " +
                        "WHERE type = 'SQL' AND success = 1 ORDER BY installed_rank",
                ).use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString("version"))
                    }
                }
            }
        }
        assertEquals(listOf("1", "2", "3"), appliedVersions)

        val storedTimes = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT collect_time FROM disk_snapshot ORDER BY collect_time",
                ).use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }
        assertEquals(
            listOf(
                "2026-07-09 15:59:59.123Z",
                "2026-07-09 16:00:00.456Z",
                "2026-07-09 16:00:01.789Z",
            ),
            storedTimes,
        )

        assertEquals(
            1,
            transaction {
                maintenanceRepository.countSnapshotsBefore(atCutoff)
            },
        )

        val boundaryHistory = transaction {
            repository.findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = HistoryQuery(
                    from = atCutoff,
                    to = atCutoff,
                    order = HistoryOrder.ASC,
                ),
            )
        }
        assertEquals(
            listOf(atCutoff.toInstant().toEpochMilli()),
            boundaryHistory.snapshots.map { it.timestamp.toEpochMilliseconds() },
        )

        assertEquals(
            1,
            transaction {
                maintenanceRepository.deleteSnapshotsBefore(atCutoff)
            },
        )
        val remainingHistory = transaction {
            repository.findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = HistoryQuery(order = HistoryOrder.ASC),
            )
        }
        assertEquals(
            listOf(atCutoff, after).map { it.toInstant().toEpochMilli() },
            remainingHistory.snapshots.map { it.timestamp.toEpochMilliseconds() },
        )

        val indexes = DriverManager.getConnection(jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA index_list('disk_snapshot')").use { rows ->
                    buildSet {
                        while (rows.next()) add(rows.getString("name"))
                    }
                }
            }
        }
        assertTrue("disk_snapshot_collect_time" in indexes)
        assertTrue("disk_snapshot_latest_lookup" in indexes)
        assertTrue("disk_snapshot_node_id_device_key_collect_time" in indexes)
    }
}
