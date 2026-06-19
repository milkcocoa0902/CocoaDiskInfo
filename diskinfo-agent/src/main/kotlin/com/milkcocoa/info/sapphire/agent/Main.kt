package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.milkcocoa.info.sapphire.agent.config.AgentConfig
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
import com.milkcocoa.info.sapphire.agent.config.AgentConfigLoader
import com.milkcocoa.info.sapphire.agent.config.AgentConfigParseException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class SapphireAgent : CliktCommand(name = "cocoadiskinfo-agent") {
    override fun run() = Unit
}

internal fun createSapphireAgentCommand(
    runtime: SapphireCommandRuntime = ProductionSapphireCommandRuntime,
): CliktCommand = SapphireAgent()
    .subcommands(
        OneshotCommand(runtime),
        StandaloneCommand(runtime),
        DbCommand().subcommands(DbMigrateCommand(runtime)),
    )

private abstract class ConfiguredCommand(
    name: String,
    protected val runtime: SapphireCommandRuntime,
) : CliktCommand(name = name) {
    protected val configPath: Path? by option("--config")
        .path(
            mustExist = true,
            canBeFile = true,
            canBeDir = false,
            mustBeWritable = false,
            mustBeReadable = true,
            canBeSymlink = true,
        )
        .help("TOML configuration file")

    protected fun loadConfig(): AgentConfig {
        return try {
            AgentConfigLoader.load(configPath)
        } catch (error: AgentConfigParseException) {
            throw UsageError(error.message ?: "Invalid config file.")
        }
    }
}

private abstract class DeviceCommand(
    name: String,
    runtime: SapphireCommandRuntime,
) : ConfiguredCommand(name, runtime) {
    private val scan: Boolean? by option("--scan")
        .nullableFlag()
        .help("Scan all devices")

    private val device: Path? by option("--device")
        .path(
            mustExist = true,
            canBeFile = true,
            canBeDir = false,
            mustBeWritable = false,
            mustBeReadable = true,
            canBeSymlink = true,
        )
        .help("Device path to query")

    protected fun resolveTarget(config: AgentConfig): TargetDevice {
        if (scan == true && device != null) {
            throw UsageError("Use either --scan or --device, not both.")
        }

        if (scan == true) return TargetDevice.Scan
        device?.let { return TargetDevice.Explicit(it.absolutePathString()) }

        val configuredScan = config.smartctl.scan == true
        val configuredDevice = config.smartctl.device?.takeIf { it.isNotBlank() }
        if (configuredScan && configuredDevice != null) {
            throw UsageError("Config [smartctl] must not set both scan=true and device.")
        }

        return when {
            configuredScan -> TargetDevice.Scan
            configuredDevice != null -> TargetDevice.Explicit(
                Paths.get(configuredDevice).toAbsolutePath().normalize().toString(),
            )
            else -> throw UsageError("Command requires --scan or --device, or [smartctl] scan/device in config.")
        }
    }
}

private class OneshotCommand(
    runtime: SapphireCommandRuntime,
) : DeviceCommand("oneshot", runtime) {
    private val output: OutputMode? by option("--output")
        .enum<OutputMode>(ignoreCase = true, key = { it.name })
        .help("Output format")

    private val persist: Boolean? by option("--persist")
        .nullableFlag("--no-persist")
        .help("Persist SMART snapshot to SQLite")

    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val config = loadConfig()
        val target = resolveTarget(config)
        val effectiveOutput = resolveOutput(config, output, OutputMode.DEFAULT)
        val shouldPersist = persist ?: config.runtime.persist ?: false
        val effectiveDbUrl = resolveDbUrl(config, dbUrl)

        runtime.run(
            SapphireCommandRequest.Oneshot(
                target = target,
                outputMode = effectiveOutput,
                persist = shouldPersist,
                dbUrl = effectiveDbUrl,
            ),
        )
    }
}

private class StandaloneCommand(
    runtime: SapphireCommandRuntime,
) : DeviceCommand("standalone", runtime) {
    private val intervalSeconds: Long? by option("--interval-seconds")
        .long()
        .help("Collection interval in seconds")

    private val port: Int? by option("--port")
        .int()
        .help("HTTP API port")

    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val config = loadConfig()
        val target = resolveTarget(config)
        val effectiveInterval = resolveIntervalSeconds(config, intervalSeconds)
        val effectivePort = resolvePort(config, port)
        val effectiveDbUrl = resolveDbUrl(config, dbUrl)

        runtime.run(
            SapphireCommandRequest.Standalone(
                target = target,
                intervalSeconds = effectiveInterval,
                port = effectivePort,
                dbUrl = effectiveDbUrl,
            ),
        )
    }
}

private class DbCommand : CliktCommand(name = "db") {
    override fun run() = Unit
}

private class DbMigrateCommand(
    runtime: SapphireCommandRuntime,
) : ConfiguredCommand("migrate", runtime) {
    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val config = loadConfig()
        val effectiveDbUrl = resolveDbUrl(config, dbUrl)
        runtime.run(
            SapphireCommandRequest.DbMigrate(
                dbUrl = effectiveDbUrl,
            ),
        )
    }
}

private fun resolveOutput(
    config: AgentConfig,
    cliOutput: OutputMode?,
    default: OutputMode,
): OutputMode {
    return cliOutput ?: config.output.mode?.let { mode ->
        OutputMode.entries.firstOrNull { it.name.equals(mode, ignoreCase = true) }
            ?: throw UsageError("Config [output].mode must be one of: ${OutputMode.entries.joinToString { it.name }}.")
    } ?: default
}

private fun resolveDbUrl(config: AgentConfig, cliDbUrl: String?): String {
    return cliDbUrl
        ?: config.storage.jdbcUrl?.takeIf { it.isNotBlank() }
        ?: AgentConfigDefaults.JDBC_URL
}

private fun resolveIntervalSeconds(config: AgentConfig, cliIntervalSeconds: Long?): Long {
    val interval = cliIntervalSeconds
        ?: config.runtime.intervalSeconds
        ?: AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS
    if (interval <= 0) {
        throw UsageError("Collection interval must be greater than 0 seconds.")
    }
    return interval
}

private fun resolvePort(config: AgentConfig, cliPort: Int?): Int {
    val port = cliPort
        ?: config.http.port
        ?: AgentConfigDefaults.HTTP_PORT
    if (port !in 1..65535) {
        throw UsageError("HTTP port must be between 1 and 65535.")
    }
    return port
}

fun main(args: Array<String>) = createSapphireAgentCommand().main(args)
