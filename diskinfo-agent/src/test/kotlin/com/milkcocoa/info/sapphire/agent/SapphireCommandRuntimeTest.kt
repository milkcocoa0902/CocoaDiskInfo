package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnection
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSchemaValidator
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class SapphireCommandRuntimeTest {
    @Test
    fun `non persisted oneshot and migrate do not open a hikari storage connection`() {
        var connectionCount = 0
        val executors = mutableListOf<Any>()
        val runtime = runtime(
            storageConnectionProvider = StorageConnectionProvider {
                connectionCount += 1
                error("Storage connection should not be opened.")
            },
            executorRunner = SapphireExecutorRunner { executors += it },
        )

        runtime.run(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Scan,
                outputMode = OutputMode.DEFAULT,
                persist = false,
                dbUrl = "jdbc:sqlite:/tmp/unused-oneshot.db",
                deviceIdentityNamespaceSalt = "test",
            ),
        )
        runtime.run(
            SapphireCommandRequest.DbMigrate(
                dbUrl = "jdbc:sqlite:/tmp/flyway-owned.db",
            ),
        )

        assertEquals(0, connectionCount)
        assertEquals(2, executors.size)
    }

    @Test
    fun `persisted oneshot and cleanup close their hikari storage connections`() {
        val openedConnections = mutableListOf<StorageConnection>()
        val storageConnectionProvider = StorageConnectionProvider { settings ->
            StorageConnectionFactory.connect(settings).also(openedConnections::add)
        }
        val runtime = runtime(
            storageConnectionProvider = storageConnectionProvider,
            executorRunner = SapphireExecutorRunner { },
        )

        runtime.run(
            SapphireCommandRequest.Oneshot(
                target = TargetDevice.Scan,
                outputMode = OutputMode.DEFAULT,
                persist = true,
                storage = temporarySqliteStorage(),
                deviceIdentityNamespaceSalt = "test",
            ),
        )
        runtime.run(
            SapphireCommandRequest.DbCleanup(
                storage = temporarySqliteStorage(),
                rawSnapshotDays = 30,
                dryRun = true,
                vacuumAfterCleanup = false,
            ),
        )

        assertEquals(2, openedConnections.size)
        assertTrue(openedConnections.all(StorageConnection::isClosed))
    }

    @Test
    fun `standalone rejects a stale schema before opening its runtime connection`() {
        var connectionCount = 0
        val runtime = runtime(
            storageConnectionProvider = StorageConnectionProvider {
                connectionCount += 1
                error("Storage connection should not be opened.")
            },
            executorRunner = SapphireExecutorRunner { },
            schemaValidator = object : StorageSchemaValidator {
                override fun requireCurrent(settings: StorageSettings) {
                    error("Run db migrate first.")
                }
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            runtime.run(
                SapphireCommandRequest.Standalone(
                    target = TargetDevice.Scan,
                    outputMode = OutputMode.DEFAULT,
                    intervalSeconds = 60,
                    host = "127.0.0.1",
                    port = 14631,
                    storage = temporarySqliteStorage(),
                    deviceIdentityNamespaceSalt = "test",
                    rawSnapshotDays = 30,
                    cleanupOnStartup = true,
                    cleanupIntervalHours = 24,
                    vacuumAfterCleanup = false,
                ),
            )
        }

        assertEquals("Run db migrate first.", error.message)
        assertEquals(0, connectionCount)
    }

    private fun runtime(
        storageConnectionProvider: StorageConnectionProvider,
        executorRunner: SapphireExecutorRunner,
        schemaValidator: StorageSchemaValidator = object : StorageSchemaValidator {
            override fun requireCurrent(settings: StorageSettings) = Unit
        },
    ) = ProductionSapphireCommandRuntime(
        snapshotUseCaseFactory = SnapshotUseCaseFactory { unusedSnapshotUseCase() },
        snapshotMaintenanceUseCaseFactory = SnapshotMaintenanceUseCaseFactory {
            unusedSnapshotMaintenanceUseCase()
        },
        storageConnectionProvider = storageConnectionProvider,
        executorRunner = executorRunner,
        schemaValidator = schemaValidator,
    )

    private fun temporarySqliteStorage(): StorageSettings = StorageSettings.fromJdbcUrl(
        "jdbc:sqlite:${createTempFile().absolutePathString()}",
    )
}

private fun unusedSnapshotUseCase(): SnapshotUseCase = object : SnapshotUseCase {
    override suspend fun saveSnapshot(snapshot: DiskSnapshot) = error("Executor must not run in this test.")

    override suspend fun findLatestNodes(): List<RawNodeSnapshot> = error("Executor must not run in this test.")

    override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? =
        error("Executor must not run in this test.")

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory = error("Executor must not run in this test.")
}

private fun unusedSnapshotMaintenanceUseCase(): SnapshotMaintenanceUseCase =
    object : SnapshotMaintenanceUseCase {
        override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult =
            error("Executor must not run in this test.")
    }
