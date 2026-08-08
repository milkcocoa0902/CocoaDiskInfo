package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

/**
 * Runs only when an explicit PostgreSQL test database is configured through
 * `cocoadiskinfo.test.postgresql.jdbcUrl` or
 * `COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL`.
 *
 * The configured database must be dedicated to this test because cleanup is a
 * whole-table retention operation by design.
 */
@OptIn(ExperimentalUuidApi::class)
class PostgreSqlStorageIntegrationTest {
    @Test
    fun `postgresql supports migration repository cleanup and vacuum contracts`() = runBlocking {
        val storage = postgreSqlTestSettings()
        assumeTrue(
            storage != null,
            "Set cocoadiskinfo.test.postgresql.jdbcUrl or " +
                "COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL to run the PostgreSQL integration test.",
        )
        checkNotNull(storage)

        val migrator = createStorageMigratorFactory().create(storage)
        migrator.migrate(storage)
        migrator.migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            requireEmptyDedicatedDatabase(connection)
            assertSingleV1Migration(connection)

            val transactionRunner = ExposedTransactionRunner(connection.database)
            val repository = ExposedDiskSnapshotRepository()
            val maintenanceRepository = ExposedSnapshotMaintenanceRepository()
            val maintenanceOperation = createStorageMaintenanceOperation(connection)
            val deviceKey = "phase4e-integration-device"
            val olderTimestampMillis = 1_735_732_800_123L
            val newerTimestampMillis = 1_735_732_860_456L
            val cutoff = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(newerTimestampMillis),
                ZoneOffset.UTC,
            )

            try {
                transactionRunner.readWrite {
                    repository.insert(
                        testDiskSnapshot(
                            deviceKey = deviceKey,
                            timestampMillis = olderTimestampMillis,
                            temperatureCelsius = 30,
                        ),
                    )
                    repository.insert(
                        testDiskSnapshot(
                            deviceKey = deviceKey,
                            timestampMillis = newerTimestampMillis,
                            temperatureCelsius = 31,
                        ),
                    )
                }

                val latest = transactionRunner.readOnly {
                    repository.findLatestByDeviceKey(deviceKey)
                }
                assertNotNull(latest)
                assertEquals(newerTimestampMillis, latest.timestamp.toEpochMilliseconds())
                assertEquals(31, latest.temperatureCelsius)

                val history = transactionRunner.readOnly {
                    repository.findHistory(
                        nodeId = NodeIdentity.nodeId,
                        deviceKey = deviceKey,
                        query = HistoryQuery(limit = 10),
                    )
                }
                assertEquals(
                    listOf(newerTimestampMillis, olderTimestampMillis),
                    history.snapshots.map { it.timestamp.toEpochMilliseconds() },
                )

                assertPostgreSqlColumnTypesAndStoredInstant(
                    connection = connection,
                    expectedTimestampMillis = olderTimestampMillis,
                )

                assertEquals(
                    1,
                    transactionRunner.readOnly {
                        maintenanceRepository.countSnapshotsBefore(cutoff)
                    },
                )
                assertEquals(
                    1,
                    transactionRunner.readWrite {
                        maintenanceRepository.deleteSnapshotsBefore(cutoff)
                    },
                )
                assertEquals(
                    0,
                    transactionRunner.readOnly {
                        maintenanceRepository.countSnapshotsBefore(cutoff)
                    },
                )

                maintenanceOperation.vacuum()

                val remaining = transactionRunner.readOnly {
                    repository.findHistory(
                        nodeId = NodeIdentity.nodeId,
                        deviceKey = deviceKey,
                        query = HistoryQuery(limit = 10),
                    )
                }
                assertEquals(listOf(newerTimestampMillis), remaining.snapshots.map {
                    it.timestamp.toEpochMilliseconds()
                })
            } finally {
                connection.useJdbcConnection { jdbcConnection ->
                    jdbcConnection.prepareStatement(
                        "DELETE FROM disk_snapshot WHERE device_key = ?",
                    ).use { statement ->
                        statement.setString(1, deviceKey)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    private fun assertSingleV1Migration(connection: StorageConnection) {
        val appliedVersions = connection.useJdbcConnection { jdbcConnection ->
            jdbcConnection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT version FROM flyway_schema_history " +
                        "WHERE type = 'SQL' AND success ORDER BY installed_rank",
                ).use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString("version"))
                    }
                }
            }
        }
        assertEquals(listOf("1"), appliedVersions)
    }

    private fun requireEmptyDedicatedDatabase(connection: StorageConnection) {
        val rowCount = connection.useJdbcConnection { jdbcConnection ->
            jdbcConnection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM disk_snapshot").use { rows ->
                    check(rows.next())
                    rows.getLong(1)
                }
            }
        }
        check(rowCount == 0L) {
            "PostgreSQL integration tests require an empty, dedicated test database; " +
                "disk_snapshot currently contains $rowCount row(s)."
        }
    }

    private fun assertPostgreSqlColumnTypesAndStoredInstant(
        connection: StorageConnection,
        expectedTimestampMillis: Long,
    ) {
        val columnTypes = connection.useJdbcConnection { jdbcConnection ->
            jdbcConnection.prepareStatement(
                """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'disk_snapshot'
                  AND column_name IN ('snapshot_id', 'node_id', 'collect_time', 'snapshot_json')
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    buildMap {
                        while (rows.next()) {
                            put(rows.getString("column_name"), rows.getString("data_type"))
                        }
                    }
                }
            }
        }
        assertEquals("uuid", columnTypes["snapshot_id"])
        assertEquals("uuid", columnTypes["node_id"])
        assertEquals("timestamp with time zone", columnTypes["collect_time"])
        assertEquals("jsonb", columnTypes["snapshot_json"])

        val cleanupIndexExists = connection.useJdbcConnection { jdbcConnection ->
            jdbcConnection.prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_indexes
                    WHERE schemaname = current_schema()
                      AND tablename = 'disk_snapshot'
                      AND indexname = 'disk_snapshot_collect_time'
                )
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next())
                    rows.getBoolean(1)
                }
            }
        }
        assertTrue(cleanupIndexExists)

        connection.useJdbcConnection { jdbcConnection ->
            jdbcConnection.prepareStatement(
                """
                SELECT collect_time, pg_typeof(snapshot_json)::text AS json_type,
                       snapshot_json ->> 'deviceKey' AS json_device_key
                FROM disk_snapshot
                WHERE collect_time = (SELECT MIN(collect_time) FROM disk_snapshot)
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(
                        Instant.ofEpochMilli(expectedTimestampMillis),
                        rows.getObject("collect_time", OffsetDateTime::class.java).toInstant(),
                    )
                    assertEquals("jsonb", rows.getString("json_type"))
                    assertEquals("phase4e-integration-device", rows.getString("json_device_key"))
                }
            }
        }
    }

    private fun postgreSqlTestSettings(): StorageSettings? {
        val jdbcUrl = explicitSetting(
            systemProperty = "cocoadiskinfo.test.postgresql.jdbcUrl",
            environmentVariable = "COCOADISKINFO_TEST_POSTGRESQL_JDBC_URL",
        ) ?: return null
        return StorageSettings(
            backend = StorageBackend.POSTGRESQL,
            jdbcUrl = jdbcUrl,
            username = explicitSetting(
                systemProperty = "cocoadiskinfo.test.postgresql.username",
                environmentVariable = "COCOADISKINFO_TEST_POSTGRESQL_USERNAME",
            ),
            password = explicitSetting(
                systemProperty = "cocoadiskinfo.test.postgresql.password",
                environmentVariable = "COCOADISKINFO_TEST_POSTGRESQL_PASSWORD",
            ),
        )
    }

    private fun explicitSetting(
        systemProperty: String,
        environmentVariable: String,
    ): String? = System.getProperty(systemProperty)
        ?.takeIf(String::isNotBlank)
        ?: System.getenv(environmentVariable)?.takeIf(String::isNotBlank)
}
