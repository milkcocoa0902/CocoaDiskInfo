package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import com.milkcocoa.info.sapphire.agent.identity.UuidV5DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.server.SapphireAgentServer
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.CompositeSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.RepositorySnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import kotlinx.coroutines.runBlocking
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
    ) : SapphireCommandRequest {
        constructor(
            target: TargetDevice,
            outputMode: OutputMode,
            intervalSeconds: Long,
            host: String,
            port: Int,
            dbUrl: String,
            deviceIdentityNamespaceSalt: String,
        ) : this(
            target = target,
            outputMode = outputMode,
            intervalSeconds = intervalSeconds,
            host = host,
            port = port,
            storage = StorageSettings.fromJdbcUrl(dbUrl),
            deviceIdentityNamespaceSalt = deviceIdentityNamespaceSalt,
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
}

internal interface SapphireCommandRuntime {
    fun run(request: SapphireCommandRequest)
}

internal object ProductionSapphireCommandRuntime : SapphireCommandRuntime {
    override fun run(request: SapphireCommandRequest) {
        when (request) {
            is SapphireCommandRequest.Oneshot -> runOneshot(request)
            is SapphireCommandRequest.Standalone -> runStandalone(request)
            is SapphireCommandRequest.DbMigrate -> runExecutor(
                SapphireExecutor.Migrate(storage = request.storage),
            )
        }
    }

    private fun runOneshot(request: SapphireCommandRequest.Oneshot) {
        setupConsoleOutput(request.outputMode)

        if (request.persist) {
            StorageConnectionFactory.connect(request.storage).use {
                runExecutor(
                    SapphireExecutor.Oneshot(
                        device = request.target,
                        collector = createCollector(request.deviceIdentityNamespaceSalt),
                        sink = createSnapshotSink(DiskSnapshotRepository()),
                    ),
                )
            }
        } else {
            runExecutor(
                SapphireExecutor.Oneshot(
                    device = request.target,
                    collector = createCollector(request.deviceIdentityNamespaceSalt),
                    sink = createSnapshotSink(repository = null),
                ),
            )
        }
    }

    private fun runStandalone(request: SapphireCommandRequest.Standalone) {
        setupConsoleOutput(request.outputMode)
        StorageConnectionFactory.connect(request.storage).use {
            val repository = DiskSnapshotRepository()

            runExecutor(
                SapphireExecutor.Standalone(
                    device = request.target,
                    collectionInterval = request.intervalSeconds.seconds,
                    collector = createCollector(request.deviceIdentityNamespaceSalt),
                    sink = createSnapshotSink(repository),
                    server = SapphireAgentServer(
                        repository = repository,
                        host = request.host,
                        port = request.port,
                    ),
                ),
            )
        }
    }

    private fun runExecutor(executor: SapphireExecutor) {
        runBlocking {
            try {
                executor.execute()
            } finally {
                Colotok.forceShutdown()
            }
        }
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

    private fun createSnapshotSink(repository: DiskSnapshotRepository?): SnapshotSink {
        val outputSink = ColotokSnapshotSink()
        return if (repository == null) {
            outputSink
        } else {
            CompositeSnapshotSink(
                outputSink,
                RepositorySnapshotSink(repository),
            )
        }
    }
}
