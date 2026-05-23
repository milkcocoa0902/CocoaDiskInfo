package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.groups.default
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotTable
import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommand
import com.milkcocoa.info.sapphire.agent.smartctl.converter.toDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToHexString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.io.path.absolutePathString
import kotlin.time.Duration.Companion.seconds

class SapphireAgent : CliktCommand() {
    enum class OutputMode {
        DEFAULT,
        JSON,
        TEXT,
        CBOR,
    }

    sealed interface TargetDevice {
        data object Scan : TargetDevice
        data class Explicit(val device: String) : TargetDevice
    }

    sealed interface ExecutionMode {
        data object Agent : ExecutionMode
        data object Oneshot : ExecutionMode
        data object Migration : ExecutionMode
    }

    val device: TargetDevice? by mutuallyExclusiveOptions(
        option1 = option("--scan").flag(default = false).help("Scan all devices").convert { TargetDevice.Scan },
        option2 = option("--device").path(
            mustExist = true,
            canBeFile = true,
            canBeDir = false,
            mustBeWritable = false,
            mustBeReadable = true,
            canBeSymlink = true,
        ).help("Device path to query").convert { TargetDevice.Explicit(it.absolutePathString()) },
    ).single()

    val executionMode by mutuallyExclusiveOptions(
        option("--agent").flag(default = false).help("Run as agent").convert { ExecutionMode.Agent },
        option("--oneshot").flag(default = false).help("Run as oneshot").convert { ExecutionMode.Oneshot },
        option("--migration").flag(default = false).help("Run as migration").convert { ExecutionMode.Migration },
    ).default(ExecutionMode.Oneshot)

    val output by option("--output").enum<OutputMode>(ignoreCase = true, key = { it.name }).help("Output format")
    val persist by option("--persist").flag(default = false).help("Persist SMART snapshot to SQLite (oneshot only)")

    override fun run() {
        validateArguments()
        if (shouldUseDatabase()) {
            Database.connect("jdbc:sqlite:./sapphire.db", "org.sqlite.JDBC")
        }

        val effectiveOutput = when (executionMode) {
            is ExecutionMode.Agent -> OutputMode.DEFAULT
            is ExecutionMode.Oneshot -> output ?: OutputMode.DEFAULT
            is ExecutionMode.Migration -> OutputMode.DEFAULT
        }

        ColotokProviderFactory
            .create(
                executionMode = executionMode,
                outputMode = effectiveOutput,
                persist = persist,
            )
            ?.also { ColotokLoggerContext.setDefault(it) }

        when (executionMode) {
            is ExecutionMode.Agent -> runSapphireAgent()
            is ExecutionMode.Oneshot -> {
                runBlocking {
                    runSapphireOneshot()
                    Colotok.forceShutdown()
                }
            }

            is ExecutionMode.Migration -> runSapphireMigration()
        }
    }

    private fun shouldUseDatabase(): Boolean {
        return when (executionMode) {
            is ExecutionMode.Agent -> true
            is ExecutionMode.Oneshot -> persist
            is ExecutionMode.Migration -> false
        }
    }

    private fun validateArguments() {
        when (executionMode) {
            is ExecutionMode.Agent -> {
                if (device == null) throw UsageError("Agent mode requires either --scan or --device.")
                if (output != null) throw UsageError("Agent mode does not allow --output.")
                if (persist) throw UsageError("Agent mode does not allow --persist.")
            }

            is ExecutionMode.Oneshot -> {
                if (device == null) throw UsageError("Oneshot mode requires either --scan or --device.")
            }

            is ExecutionMode.Migration -> {
                if (device != null || output != null || persist) {
                    throw UsageError("Migration mode does not allow --scan, --device, --output, or --persist.")
                }
            }
        }
    }

    private fun runSapphireMigration() {
        Database.connect("jdbc:sqlite:./sapphire.db", "org.sqlite.JDBC")
        transaction {
            SchemaUtils.create(DiskSnapshotTable)
        }
        println("Migration completed.")
    }

    private fun runSapphireAgent() {
        embeddedServer(factory = CIO, port = 14631) {
            launch {
                while (true) {
                    runSapphireOneshot()
                    delay(60.seconds)
                }
            }
        }.start(wait = true)
    }

    private suspend fun runSapphireOneshot() {
        when (device) {
            is TargetDevice.Explicit -> {
                val deviceInfo = SmartCtlCommand.DeviceInfo(
                    device = (device as TargetDevice.Explicit).device,
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

            else -> Unit
        }
    }

    private fun DiskSnapshot.print() {
        when (output) {
            null, OutputMode.DEFAULT -> Colotok.info(this)
            OutputMode.JSON -> {
                val json = Json { prettyPrint = true }
                println(json.encodeToString(this))
            }

            OutputMode.TEXT -> println(this.toString())
            OutputMode.CBOR -> {
                val cbor = Cbor {}
                println(cbor.encodeToHexString(this))
            }
        }
    }
}

fun main(args: Array<String>) = SapphireAgent().main(args)
