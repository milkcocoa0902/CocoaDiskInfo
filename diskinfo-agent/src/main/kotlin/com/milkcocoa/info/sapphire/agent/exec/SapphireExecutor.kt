package com.milkcocoa.info.sapphire.agent.exec

import com.milkcocoa.info.sapphire.agent.TargetDevice
import com.milkcocoa.info.sapphire.agent.collector.DiskSnapshotCollector
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.datastore.StorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.maintenance.PeriodicMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.SapphireServer
import com.milkcocoa.info.sapphire.agent.server.installStandaloneMaintenance
import com.milkcocoa.info.sapphire.agent.server.installPeriodicMaintenance
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

    class Hub(
        private val server: SapphireServer,
        private val maintenanceRunner: PeriodicMaintenanceRunner,
        private val cleanupOnStartup: Boolean,
        private val cleanupInterval: Duration,
    ) : SapphireExecutor {
        override suspend fun execute() {
            if (cleanupOnStartup) {
                maintenanceRunner.runCleanup()
            }
            server.start(wait = true) {
                installPeriodicMaintenance(
                    runner = maintenanceRunner,
                    cleanupInterval = cleanupInterval,
                )
            }
        }
    }

    class NodeAgent(
        private val device: TargetDevice,
        private val collectionInterval: Duration,
        private val heartbeatInterval: Duration,
        private val collector: DiskSnapshotCollector,
        private val sink: SnapshotSink,
        private val heartbeat: NodeAgentHeartbeat,
    ) : SapphireExecutor {
        override suspend fun execute() {
            kotlinx.coroutines.coroutineScope {
                val latestCycleFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
                val heartbeatJob = launch {
                    while (isActive) {
                        try {
                            heartbeat.send(latestCycleFailure.get())
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            com.milkcocoa.info.colotok.core.logger.Colotok.warn(
                                msg = "Node Agent heartbeat failed; the next heartbeat will continue.",
                                attr = mapOf("error" to (error.message ?: error::class.simpleName.orEmpty())),
                            )
                        }
                        delay(heartbeatInterval)
                    }
                }

                try {
                    while (isActive) {
                        try {
                            collectSnapshots(device, collector).forEach { snapshot ->
                                sink.write(snapshot)
                            }
                            latestCycleFailure.set(null)
                        } catch (error: kotlinx.coroutines.CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            latestCycleFailure.set(error)
                            com.milkcocoa.info.colotok.core.logger.Colotok.warn(
                                msg = "Node Agent collection or delivery failed; the next cycle will continue.",
                                attr = mapOf("error" to (error.message ?: error::class.simpleName.orEmpty())),
                            )
                        }
                        // Waiting after completion keeps slow collection/delivery jobs
                        // sequential instead of trying to catch up with overlapping work.
                        delay(collectionInterval)
                    }
                } finally {
                    heartbeatJob.cancel()
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

fun interface NodeAgentHeartbeat {
    suspend fun send(cycleFailure: Exception?)
}

private suspend fun collectSnapshots(
    device: TargetDevice,
    collector: DiskSnapshotCollector,
) = when (device) {
    is TargetDevice.Explicit -> listOf(collector.collectDevice(device.device))
    is TargetDevice.Scan -> collector.scanDevices()
}
