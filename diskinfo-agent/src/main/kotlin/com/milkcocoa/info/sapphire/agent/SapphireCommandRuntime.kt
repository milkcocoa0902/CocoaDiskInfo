package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnection
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import com.milkcocoa.info.sapphire.agent.identity.UuidV5DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.SapphireAgentServer
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.CompositeSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.RepositorySnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

internal sealed interface SapphireCommandRequest {
    data class Oneshot(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val persist: Boolean,
        val storage: StorageSettings,
        val deviceIdentityNamespaceSalt: String,
    ) : SapphireCommandRequest {
        constructor(
            target: TargetDevice,
            outputMode: OutputMode,
            persist: Boolean,
            dbUrl: String,
            deviceIdentityNamespaceSalt: String,
        ) : this(
            target = target,
            outputMode = outputMode,
            persist = persist,
            storage = StorageSettings.fromJdbcUrl(dbUrl),
            deviceIdentityNamespaceSalt = deviceIdentityNamespaceSalt,
        )

        val dbUrl: String
            get() = storage.jdbcUrl
    }

    data class Standalone(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val intervalSeconds: Long,
        val host: String,
        val port: Int,
        val storage: StorageSettings,
        val deviceIdentityNamespaceSalt: String,
        val rawSnapshotDays: Int,
        val cleanupOnStartup: Boolean,
        val cleanupIntervalHours: Long,
        val vacuumAfterCleanup: Boolean,
    ) : SapphireCommandRequest {
        constructor(
            target: TargetDevice,
            outputMode: OutputMode,
            intervalSeconds: Long,
            host: String,
            port: Int,
            dbUrl: String,
            deviceIdentityNamespaceSalt: String,
            rawSnapshotDays: Int = AgentConfigDefaults.DEFAULT_RAW_SNAPSHOT_DAYS,
            cleanupOnStartup: Boolean = AgentConfigDefaults.DEFAULT_CLEANUP_ON_STARTUP,
            cleanupIntervalHours: Long = AgentConfigDefaults.DEFAULT_CLEANUP_INTERVAL_HOURS,
            vacuumAfterCleanup: Boolean = AgentConfigDefaults.DEFAULT_VACUUM_AFTER_CLEANUP,
        ) : this(
            target = target,
            outputMode = outputMode,
            intervalSeconds = intervalSeconds,
            host = host,
            port = port,
            storage = StorageSettings.fromJdbcUrl(dbUrl),
            deviceIdentityNamespaceSalt = deviceIdentityNamespaceSalt,
            rawSnapshotDays = rawSnapshotDays,
            cleanupOnStartup = cleanupOnStartup,
            cleanupIntervalHours = cleanupIntervalHours,
            vacuumAfterCleanup = vacuumAfterCleanup,
        )

        val dbUrl: String
            get() = storage.jdbcUrl
    }

    data class DbMigrate(
        val storage: StorageSettings,
    ) : SapphireCommandRequest {
        constructor(dbUrl: String) : this(storage = StorageSettings.fromJdbcUrl(dbUrl))

        val dbUrl: String
            get() = storage.jdbcUrl
    }

    data class DbCleanup(
        val storage: StorageSettings,
        val rawSnapshotDays: Int,
        val dryRun: Boolean,
        val vacuumAfterCleanup: Boolean,
    ) : SapphireCommandRequest {
        constructor(
            dbUrl: String,
            rawSnapshotDays: Int,
            dryRun: Boolean,
            vacuumAfterCleanup: Boolean,
        ) : this(
            storage = StorageSettings.fromJdbcUrl(dbUrl),
            rawSnapshotDays = rawSnapshotDays,
            dryRun = dryRun,
            vacuumAfterCleanup = vacuumAfterCleanup,
        )

        val dbUrl: String
            get() = storage.jdbcUrl
    }
}

internal interface SapphireCommandRuntime {
    fun run(request: SapphireCommandRequest)
}

internal fun interface SnapshotUseCaseFactory {
    fun create(connection: StorageConnection): SnapshotUseCase
}

internal fun interface SnapshotMaintenanceUseCaseFactory {
    fun create(connection: StorageConnection): SnapshotMaintenanceUseCase
}

internal fun interface StorageConnectionProvider {
    fun connect(settings: StorageSettings): StorageConnection
}

internal fun interface SapphireExecutorRunner {
    fun run(executor: SapphireExecutor)
}

internal class ProductionSapphireCommandRuntime(
    private val snapshotUseCaseFactory: SnapshotUseCaseFactory,
    private val snapshotMaintenanceUseCaseFactory: SnapshotMaintenanceUseCaseFactory,
    private val storageConnectionProvider: StorageConnectionProvider =
        StorageConnectionProvider(StorageConnectionFactory::connect),
    private val executorRunner: SapphireExecutorRunner = BlockingSapphireExecutorRunner,
) : SapphireCommandRuntime {
    override fun run(request: SapphireCommandRequest) {
        when (request) {
            is SapphireCommandRequest.Oneshot -> runOneshot(request)
            is SapphireCommandRequest.Standalone -> runStandalone(request)
            is SapphireCommandRequest.DbMigrate -> runExecutor(
                SapphireExecutor.Migrate(storage = request.storage),
            )
            is SapphireCommandRequest.DbCleanup -> runDbCleanup(request)
        }
    }

    private fun runDbCleanup(request: SapphireCommandRequest.DbCleanup) {
        storageConnectionProvider.connect(request.storage).use { connection ->
            runExecutor(
                SapphireExecutor.Cleanup(
                    request = SnapshotCleanupRequest(
                        rawSnapshotDays = request.rawSnapshotDays,
                        dryRun = request.dryRun,
                        vacuumAfterCleanup = request.vacuumAfterCleanup,
                    ),
                    maintenanceUseCase = snapshotMaintenanceUseCaseFactory.create(connection),
                ),
            )
        }
    }

    private fun runOneshot(request: SapphireCommandRequest.Oneshot) {
        setupConsoleOutput(request.outputMode)

        if (request.persist) {
            storageConnectionProvider.connect(request.storage).use { connection ->
                val snapshotUseCase = snapshotUseCaseFactory.create(connection)

                runExecutor(
                    SapphireExecutor.Oneshot(
                        device = request.target,
                        collector = createCollector(request.deviceIdentityNamespaceSalt),
                        sink = createSnapshotSink(snapshotUseCase),
                    ),
                )
            }
        } else {
            runExecutor(
                SapphireExecutor.Oneshot(
                    device = request.target,
                    collector = createCollector(request.deviceIdentityNamespaceSalt),
                    sink = createSnapshotSink(snapshotUseCase = null),
                ),
            )
        }
    }

    private fun runStandalone(request: SapphireCommandRequest.Standalone) {
        setupConsoleOutput(request.outputMode)
        storageConnectionProvider.connect(request.storage).use { connection ->
            val snapshotUseCase = snapshotUseCaseFactory.create(connection)
            val maintenanceRunner = StandaloneMaintenanceRunner(
                maintenanceUseCase = snapshotMaintenanceUseCaseFactory.create(connection),
                rawSnapshotDays = request.rawSnapshotDays,
                vacuumAfterCleanup = request.vacuumAfterCleanup,
            )

            runExecutor(
                SapphireExecutor.Standalone(
                    device = request.target,
                    collectionInterval = request.intervalSeconds.seconds,
                    collector = createCollector(request.deviceIdentityNamespaceSalt),
                    sink = createSnapshotSink(snapshotUseCase),
                    server = SapphireAgentServer(
                        snapshotUseCase = snapshotUseCase,
                        host = request.host,
                        port = request.port,
                    ),
                    maintenanceRunner = maintenanceRunner,
                    cleanupOnStartup = request.cleanupOnStartup,
                    cleanupInterval = request.cleanupIntervalHours.hours,
                ),
            )
        }
    }

    private fun runExecutor(executor: SapphireExecutor) {
        executorRunner.run(executor)
    }

    private fun setupConsoleOutput(outputMode: OutputMode) {
        ColotokProviderFactory
            .create(outputMode = outputMode)
            .also { ColotokLoggerContext.setDefault(it) }
    }

    private fun createCollector(namespaceSalt: String): SmartctlCollector {
        return SmartctlCollector(
            deviceKeyDeriver = UuidV5DeviceKeyDeriver(namespaceSalt),
        )
    }

    private fun createSnapshotSink(snapshotUseCase: SnapshotUseCase?): SnapshotSink {
        val outputSink = ColotokSnapshotSink()
        return if (snapshotUseCase == null) {
            outputSink
        } else {
            CompositeSnapshotSink(
                outputSink,
                RepositorySnapshotSink(snapshotUseCase),
            )
        }
    }
}

private object BlockingSapphireExecutorRunner : SapphireExecutorRunner {
    override fun run(executor: SapphireExecutor) {
        runBlocking {
            try {
                executor.execute()
            } finally {
                Colotok.forceShutdown()
            }
        }
    }
}
