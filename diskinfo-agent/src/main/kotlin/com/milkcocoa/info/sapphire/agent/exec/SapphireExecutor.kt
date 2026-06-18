package com.milkcocoa.info.sapphire.agent.exec

import com.milkcocoa.info.sapphire.agent.SapphireAgent
import com.milkcocoa.info.sapphire.agent.SapphireAgent.TargetDevice
import com.milkcocoa.info.sapphire.agent.collector.DiskSnapshotCollector
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotTable
import com.milkcocoa.info.sapphire.agent.server.SapphireAgentServer
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import kotlin.time.Duration.Companion.seconds

sealed interface SapphireExecutor {
    suspend fun execute()

    class Oneshot(
        private val device: SapphireAgent.TargetDevice,
        private val collector: DiskSnapshotCollector = SmartctlCollector(),
        private val sink: SnapshotSink = ColotokSnapshotSink(),
    ) : SapphireExecutor {
        override suspend fun execute() {
            collectSnapshots(device, collector).forEach { snapshot ->
                sink.write(snapshot)
            }
        }
    }

    class Agent(
        private val device: SapphireAgent.TargetDevice,
        private val collector: DiskSnapshotCollector = SmartctlCollector(),
        private val sink: SnapshotSink = ColotokSnapshotSink(),
        private val server: SapphireAgentServer = SapphireAgentServer(),
    ) : SapphireExecutor {
        override suspend fun execute() {
            val oneshot = Oneshot(
                device = device,
                collector = collector,
                sink = sink,
            )
            server.start(wait = true) {
                launch {
                    while (isActive) {
                        launch {
                            oneshot.execute()
                        }
                        delay(60.seconds)
                    }
                }
            }
        }
    }

    class Migrate : SapphireExecutor {
        override suspend fun execute() {
            Database.connect("jdbc:sqlite:./sapphire.db", "org.sqlite.JDBC")
            transaction {
                exec("DROP TABLE IF EXISTS disk_snapshot")
                MigrationUtils
                    .statementsRequiredForDatabaseMigration(DiskSnapshotTable, withLogs = true)
                    .forEach { exec(it) }
            }
            println("Migration completed.")
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
