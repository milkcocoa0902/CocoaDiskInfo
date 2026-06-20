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
import com.milkcocoa.info.sapphire.agent.config.AgentConfigOverrides
import com.milkcocoa.info.sapphire.agent.config.AgentConfigParseException
import com.milkcocoa.info.sapphire.agent.config.AgentConfigResolver
import com.milkcocoa.info.sapphire.agent.config.AgentConfigValidationException
import com.milkcocoa.info.sapphire.agent.config.EffectiveDbMigrateConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveOneshotConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveStandaloneConfig
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class SapphireAgent : CliktCommand(name = "cocoadiskinfo-agent") {
    override fun run() = Unit
}

internal fun createSapphireAgentCommand(
    runtime: SapphireCommandRuntime = ProductionSapphireCommandRuntime,
    environment: Map<String, String> = System.getenv(),
    defaultConfigPath: Path? = Paths.get(AgentConfigDefaults.DEFAULT_CONFIG_PATH),
): CliktCommand = SapphireAgent()
    .subcommands(
        OneshotCommand(runtime, environment, defaultConfigPath),
        StandaloneCommand(runtime, environment, defaultConfigPath),
        DbCommand().subcommands(DbMigrateCommand(runtime, environment, defaultConfigPath)),
    )

private abstract class ConfiguredCommand(
    name: String,
    protected val runtime: SapphireCommandRuntime,
    private val environment: Map<String, String>,
    private val defaultConfigPath: Path?,
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

    protected fun resolveOneshotConfig(overrides: AgentConfigOverrides): EffectiveOneshotConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveOneshot(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    protected fun resolveStandaloneConfig(overrides: AgentConfigOverrides): EffectiveStandaloneConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveStandalone(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    protected fun resolveDbMigrateConfig(overrides: AgentConfigOverrides): EffectiveDbMigrateConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveDbMigrate(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    private fun <T> resolveUsageErrors(block: () -> T): T {
        return try {
            block()
        } catch (error: AgentConfigParseException) {
            throw UsageError(error.message ?: "Invalid config file.")
        } catch (error: AgentConfigValidationException) {
            throw UsageError(error.message ?: "Invalid runtime configuration.")
        }
    }

    private fun loadConfig(): AgentConfig {
        return AgentConfigLoader.load(configPath, defaultConfigPath)
    }
}

private abstract class DeviceCommand(
    name: String,
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : ConfiguredCommand(name, runtime, environment, defaultConfigPath) {
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

    protected fun deviceOverrides(): AgentConfigOverrides {
        return AgentConfigOverrides(
            scan = scan,
            device = device?.absolutePathString(),
        )
    }
}

private class OneshotCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : DeviceCommand("oneshot", runtime, environment, defaultConfigPath) {
    private val output: OutputMode? by option("--output")
        .enum<OutputMode>(ignoreCase = true, key = { it.name })
        .help("Output format")

    private val persist: Boolean? by option("--persist")
        .nullableFlag("--no-persist")
        .help("Persist SMART snapshot to SQLite")

    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val effective = resolveOneshotConfig(
            deviceOverrides().copy(
                outputMode = output,
                persist = persist,
                jdbcUrl = dbUrl,
            ),
        )

        runtime.run(
            SapphireCommandRequest.Oneshot(
                target = effective.target,
                outputMode = effective.outputMode,
                persist = effective.persist,
                dbUrl = effective.jdbcUrl,
                deviceIdentityNamespaceSalt = effective.deviceIdentityNamespaceSalt,
            ),
        )
    }
}

private class StandaloneCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : DeviceCommand("standalone", runtime, environment, defaultConfigPath) {
    private val intervalSeconds: Long? by option("--interval-seconds")
        .long()
        .help("Collection interval in seconds")

    private val output: OutputMode? by option("--output")
        .enum<OutputMode>(ignoreCase = true, key = { it.name })
        .help("Snapshot console output format")

    private val host: String? by option("--host")
        .help("HTTP API listen host")

    private val port: Int? by option("--port")
        .int()
        .help("HTTP API port")

    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val effective = resolveStandaloneConfig(
            deviceOverrides().copy(
                outputMode = output,
                intervalSeconds = intervalSeconds,
                host = host,
                port = port,
                jdbcUrl = dbUrl,
            ),
        )

        runtime.run(
            SapphireCommandRequest.Standalone(
                target = effective.target,
                outputMode = effective.outputMode,
                intervalSeconds = effective.intervalSeconds,
                host = effective.host,
                port = effective.port,
                dbUrl = effective.jdbcUrl,
                deviceIdentityNamespaceSalt = effective.deviceIdentityNamespaceSalt,
            ),
        )
    }
}

private class DbCommand : CliktCommand(name = "db") {
    override fun run() = Unit
}

private class DbMigrateCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : ConfiguredCommand("migrate", runtime, environment, defaultConfigPath) {
    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    override fun run() {
        val effective = resolveDbMigrateConfig(
            AgentConfigOverrides(
                jdbcUrl = dbUrl,
            ),
        )

        runtime.run(
            SapphireCommandRequest.DbMigrate(
                dbUrl = effective.jdbcUrl,
            ),
        )
    }
}

fun main(args: Array<String>) = createSapphireAgentCommand().main(args)
