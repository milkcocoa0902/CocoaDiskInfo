package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.NodeIdentity.nodeId
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
    fun `single v1 uses UTC Z and preserves strict cleanup and history boundaries`() {
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
                testDiskSnapshot(
                    deviceKey = deviceKey,
                    timestampMillis = before.toInstant().toEpochMilli(),
                ),
            )
            repository.insert(
                testDiskSnapshot(
                    deviceKey = deviceKey,
                    timestampMillis = atCutoff.toInstant().toEpochMilli(),
                ),
            )
            repository.insert(
                testDiskSnapshot(
                    deviceKey = deviceKey,
                    timestampMillis = after.toInstant().toEpochMilli(),
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
        assertEquals(listOf("1"), appliedVersions)

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
        assertTrue("disk_snapshot_node_id_device_key_collect_time" in indexes)
    }
}
