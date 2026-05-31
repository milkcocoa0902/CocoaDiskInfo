package com.milkcocoa.info.sapphire.agent.exec

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.SapphireAgent
import com.milkcocoa.info.sapphire.agent.SapphireAgent.TargetDevice
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotTable
import com.milkcocoa.info.sapphire.agent.server.installSapphireAgentApi
import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand
import com.milkcocoa.info.sapphire.agent.smartctl.converter.toDiskSnapshot
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import kotlin.time.Duration.Companion.seconds

sealed interface SapphireExecutor {
    suspend fun execute()

    class Oneshot(
        private val device: SapphireAgent.TargetDevice,
    ) : SapphireExecutor {
        override suspend fun execute() {
            when (device) {
                is TargetDevice.Explicit -> {
                    val deviceInfo = SmartCtlCommand.DeviceInfo(
                        device = device.device,
                    ).execute()

                    val snapshot = deviceInfo.output
                    val diskSnapshot = snapshot.toDiskSnapshot()
                    Colotok.info(diskSnapshot)
                }

                is TargetDevice.Scan -> {
                    val scanResult = SmartCtlCommand.DescribeDevices.execute()
                    val diskSnapshots = scanResult.output.devices.map {
                        withContext(Dispatchers.Default) {
                            async {
                                runCatching {
                                    val deviceInfo = SmartCtlCommand.DeviceInfo(
                                        device = it.name,
                                    ).execute()
                                    val snapshot = deviceInfo.output
                                    snapshot.toDiskSnapshot()
                                }.getOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                    diskSnapshots.forEach { Colotok.info(it) }
                }
            }
        }
    }

    class Agent(
        private val device: SapphireAgent.TargetDevice,
    ) : SapphireExecutor {
        override suspend fun execute() {
            val oneshot = Oneshot(device)
            embeddedServer(
                factory = CIO,
                port = 14631,
            ) {
                installSapphireAgentApi()

                launch {
                    while (isActive) {
                        oneshot.execute()
                        delay(60.seconds)
                    }
                }
            }.start(wait = true)
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
