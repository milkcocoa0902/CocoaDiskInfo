package com.milkcocoa.info.sapphire.agent.exec

import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.collector.DiskSnapshotCollector
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.datastore.StorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.SapphireServer
import com.milkcocoa.info.sapphire.agent.server.installStandaloneMaintenance
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface SapphireExecutor {
    suspend fun execute()

    class Oneshot(
        private val device: TargetDevice,
        private val collector: DiskSnapshotCollector = SmartctlCollector(),
        private val sink: SnapshotSink = ColotokSnapshotSink(),
    ) : SapphireExecutor {
        override suspend fun execute() {
            collectSnapshots(device, collector).forEach { snapshot ->
                sink.write(snapshot)
            }
        }
    }

    class Standalone(
        private val device: TargetDevice,
        private val collectionInterval: Duration = 60.seconds,
        private val collector: DiskSnapshotCollector = SmartctlCollector(),
        private val sink: SnapshotSink = ColotokSnapshotSink(),
        private val server: SapphireServer,
        private val maintenanceRunner: StandaloneMaintenanceRunner,
        private val cleanupOnStartup: Boolean,
        private val cleanupInterval: Duration,
    ) : SapphireExecutor {
        override suspend fun execute() {
            if (cleanupOnStartup) {
                maintenanceRunner.runCleanup()
            }

            val oneshot = Oneshot(
                device = device,
                collector = collector,
                sink = sink,
            )
            server.start(wait = true) {
                installStandaloneMaintenance(
                    runner = maintenanceRunner,
                    cleanupInterval = cleanupInterval,
                )
                launch {
                    while (isActive) {
                        launch {
                            oneshot.execute()
                        }
                        delay(collectionInterval)
                    }
                }
            }
        }
    }

    class Migrate(
        private val storage: StorageSettings,
        private val migratorFactory: StorageMigratorFactory = createStorageMigratorFactory(),
    ) : SapphireExecutor {
        constructor(jdbcUrl: String = "jdbc:sqlite:./sapphire.db") : this(StorageSettings.fromJdbcUrl(jdbcUrl))

        override suspend fun execute() {
            migratorFactory.create(storage).migrate(storage)
            println("Migration completed.")
        }
    }

    class Cleanup(
        private val request: SnapshotCleanupRequest,
        private val maintenanceUseCase: SnapshotMaintenanceUseCase,
    ) : SapphireExecutor {
        override suspend fun execute() {
            val result = maintenanceUseCase.cleanup(request)
            println("Snapshot cleanup completed.")
            println("  cutoff: ${result.cutoff}")
            println("  dryRun: ${result.dryRun}")
            result.tables.forEach { table ->
                println(
                    "  table: ${table.tableName}, matchedRows: ${table.matchedRowCount}, deletedRows: ${table.deletedRowCount}",
                )
            }
            println(
                "  vacuum: requested=${result.vacuum.requested}, executed=${result.vacuum.executed}" +
                    (result.vacuum.skippedReason?.let { ", skippedReason=$it" } ?: ""),
            )
        }
    }
}

private suspend fun collectSnapshots(
    device: TargetDevice,
    collector: DiskSnapshotCollector,
) = when (device) {
    is TargetDevice.Explicit -> listOf(collector.collectDevice(device.device))
    is TargetDevice.Scan -> collector.scanDevices()
}
