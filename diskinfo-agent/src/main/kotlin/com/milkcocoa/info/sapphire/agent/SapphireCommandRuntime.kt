package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import com.milkcocoa.info.sapphire.agent.server.SapphireAgentServer
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.CompositeSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.RepositorySnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import kotlin.time.Duration.Companion.seconds

internal sealed interface SapphireCommandRequest {
    data class Oneshot(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val persist: Boolean,
        val dbUrl: String,
    ) : SapphireCommandRequest

    data class Standalone(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val intervalSeconds: Long,
        val host: String,
        val port: Int,
        val dbUrl: String,
    ) : SapphireCommandRequest

    data class DbMigrate(
        val dbUrl: String,
    ) : SapphireCommandRequest
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
                SapphireExecutor.Migrate(jdbcUrl = request.dbUrl),
            )
        }
    }

    private fun runOneshot(request: SapphireCommandRequest.Oneshot) {
        setupConsoleOutput(request.outputMode)

        val repository = if (request.persist) {
            connectDatabase(request.dbUrl)
            DiskSnapshotRepository()
        } else {
            null
        }

        runExecutor(
            SapphireExecutor.Oneshot(
                device = request.target,
                sink = createSnapshotSink(repository),
            ),
        )
    }

    private fun runStandalone(request: SapphireCommandRequest.Standalone) {
        setupConsoleOutput(request.outputMode)
        connectDatabase(request.dbUrl)
        val repository = DiskSnapshotRepository()

        runExecutor(
            SapphireExecutor.Standalone(
                device = request.target,
                collectionInterval = request.intervalSeconds.seconds,
                sink = createSnapshotSink(repository),
                server = SapphireAgentServer(
                    repository = repository,
                    host = request.host,
                    port = request.port,
                ),
            ),
        )
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

    private fun connectDatabase(jdbcUrl: String) {
        Database.connect(jdbcUrl, "org.sqlite.JDBC")
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
