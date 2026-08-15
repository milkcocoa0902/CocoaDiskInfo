package com.milkcocoa.info.sapphire.agent.exec

import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.collector.DiskSnapshotCollector
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.SapphireServer
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupTableResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumSkippedReason
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.sql.DriverManager
import java.time.OffsetDateTime
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class SapphireExecutorTest {
    @Test
    fun `standalone startup cleanup completes before server start`() = runBlocking {
        val events = mutableListOf<String>()
        val runner = StandaloneMaintenanceRunner(
            maintenanceUseCase = object : SnapshotMaintenanceUseCase {
                override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
                    events += "cleanup"
                    return executorCleanupResult()
                }
            },
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )
        val server = object : SapphireServer {
            override fun start(wait: Boolean, module: Application.() -> Unit) {
                events += "server"
            }
        }

        SapphireExecutor.Standalone(
            device = TargetDevice.Scan,
            server = server,
            maintenanceRunner = runner,
            cleanupOnStartup = true,
            cleanupInterval = 24.hours,
        ).execute()

        assertEquals(listOf("cleanup", "server"), events)
    }

    @Test
    fun `standalone skips startup cleanup when disabled`() = runBlocking {
        val events = mutableListOf<String>()
        val runner = StandaloneMaintenanceRunner(
            maintenanceUseCase = object : SnapshotMaintenanceUseCase {
                override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
                    events += "cleanup"
                    return executorCleanupResult()
                }
            },
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )
        val server = object : SapphireServer {
            override fun start(wait: Boolean, module: Application.() -> Unit) {
                events += "server"
            }
        }

        SapphireExecutor.Standalone(
            device = TargetDevice.Scan,
            server = server,
            maintenanceRunner = runner,
            cleanupOnStartup = false,
            cleanupInterval = 24.hours,
        ).execute()

        assertEquals(listOf("server"), events)
    }

    @Test
    fun `migrate creates disk snapshot table in temporary sqlite database and is idempotent`() {
        val databaseFile = createTempFile()
        val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePathString()}"

        runBlocking {
            SapphireExecutor.Migrate(jdbcUrl = jdbcUrl).execute()
            SapphireExecutor.Migrate(jdbcUrl = jdbcUrl).execute()
        }

        assertTrue(tableExists(jdbcUrl, "disk_snapshot"), "disk_snapshot table should exist after migration.")
        assertTrue(tableExists(jdbcUrl, "flyway_schema_history"), "Flyway history table should exist after migration.")
    }

    @Test
    fun `node agent never overlaps slow collection and delivery cycles`() = runBlocking {
        var activeCollections = 0
        var maximumActiveCollections = 0
        var delivered = 0
        val collector = object : DiskSnapshotCollector {
            override suspend fun collectDevice(device: String) = error("Explicit device is not used.")

            override suspend fun scanDevices() = listOf(
                testDiskSnapshot("device-a", delivered.toLong()),
            ).also {
                activeCollections += 1
                maximumActiveCollections = maxOf(maximumActiveCollections, activeCollections)
                delay(20)
                activeCollections -= 1
            }
        }
        val job = launch {
            SapphireExecutor.NodeAgent(
                device = TargetDevice.Scan,
                collectionInterval = 1.milliseconds,
                heartbeatInterval = 5.milliseconds,
                collector = collector,
                sink = object : SnapshotSink {
                    override suspend fun write(snapshot: com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot) {
                        delivered += 1
                    }
                },
                heartbeat = NodeAgentHeartbeat { },
            ).execute()
        }

        withTimeout(2_000) {
            while (delivered < 3) delay(5)
        }
        job.cancel()
        job.join()

        assertEquals(1, maximumActiveCollections)
    }
}

private fun executorCleanupResult() = SnapshotCleanupResult(
    cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    tables = listOf(SnapshotCleanupTableResult("disk_snapshot", 0, 0)),
    dryRun = false,
    vacuum = SnapshotCleanupVacuumResult(
        requested = false,
        executed = false,
        skippedReason = SnapshotCleanupVacuumSkippedReason.NOT_REQUESTED,
    ),
)

private fun tableExists(
    jdbcUrl: String,
    tableName: String,
): Boolean =
    DriverManager.getConnection(jdbcUrl).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$tableName'",
            ).use { result ->
                result.next()
            }
        }
    }
