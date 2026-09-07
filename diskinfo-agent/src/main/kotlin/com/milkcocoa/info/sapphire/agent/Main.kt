package com.milkcocoa.info.sapphire.agent

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import com.milkcocoa.info.sapphire.agent.config.AgentConfig
import com.milkcocoa.info.sapphire.agent.auth.BootstrapTokenService
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
import com.milkcocoa.info.sapphire.agent.config.AgentConfigLoader
import com.milkcocoa.info.sapphire.agent.config.AgentConfigOverrides
import com.milkcocoa.info.sapphire.agent.config.AgentConfigParseException
import com.milkcocoa.info.sapphire.agent.config.AgentConfigResolver
import com.milkcocoa.info.sapphire.agent.config.AgentConfigValidationException
import com.milkcocoa.info.sapphire.agent.config.BootstrapTokenHostMode
import com.milkcocoa.info.sapphire.agent.config.EffectiveDbCleanupConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveDbMigrateConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveOneshotConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveStandaloneConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveHubConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveBootstrapTokenConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveNodeAgentConfig
import com.milkcocoa.info.sapphire.agent.config.EffectiveNodeAgentJoinConfig
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.ExposedDiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedSnapshotMaintenanceRepository
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMaintenanceOperation
import com.milkcocoa.info.sapphire.agent.usecase.TransactionalSnapshotMaintenanceUseCase
import com.milkcocoa.info.sapphire.agent.usecase.TransactionalSnapshotUseCase
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

/** Root CLI command; concrete lifecycle behavior lives in its mode subcommands. */
class SapphireAgent : CliktCommand(name = "cocoadiskinfo-agent") {
    override fun run() = Unit
}

/**
 * Builds the command tree with injectable runtime and configuration sources.
 * Injection keeps CLI parsing independent from production storage/network side effects.
 */
internal fun createSapphireAgentCommand(
    runtime: SapphireCommandRuntime = createProductionSapphireCommandRuntime(),
    environment: Map<String, String> = System.getenv(),
    defaultConfigPath: Path? = Paths.get(AgentConfigDefaults.DEFAULT_CONFIG_PATH),
): CliktCommand = SapphireAgent()
    .subcommands(
        OneshotCommand(runtime, environment, defaultConfigPath),
        StandaloneCommand(runtime, environment, defaultConfigPath).subcommands(
            PrincipalGroup().subcommands(
                PrincipalDisableCommand(runtime, environment, defaultConfigPath),
            ),
            BootstrapTokenGroup("client-pairing-token").subcommands(
                BootstrapTokenCreateCommand(
                    runtime = runtime,
                    environment = environment,
                    defaultConfigPath = defaultConfigPath,
                    tokenType = BootstrapTokenType.PAIRING_TOKEN,
                    hostMode = BootstrapTokenHostMode.STANDALONE,
                ),
            ),
        ),
        HubCommand(runtime, environment, defaultConfigPath).subcommands(
            PrincipalGroup().subcommands(
                PrincipalDisableCommand(runtime, environment, defaultConfigPath),
            ),
            BootstrapTokenGroup("join-token").subcommands(
                BootstrapTokenCreateCommand(
                    runtime = runtime,
                    environment = environment,
                    defaultConfigPath = defaultConfigPath,
                    tokenType = BootstrapTokenType.JOIN_TOKEN,
                    hostMode = BootstrapTokenHostMode.HUB,
                ),
            ),
            BootstrapTokenGroup("client-pairing-token").subcommands(
                BootstrapTokenCreateCommand(
                    runtime = runtime,
                    environment = environment,
                    defaultConfigPath = defaultConfigPath,
                    tokenType = BootstrapTokenType.PAIRING_TOKEN,
                    hostMode = BootstrapTokenHostMode.HUB,
                ),
            ),
        ),
        NodeAgentCommand(runtime, environment, defaultConfigPath).subcommands(
            NodeAgentJoinCommand(runtime, environment, defaultConfigPath),
        ),
        DbCommand().subcommands(
            DbMigrateCommand(runtime, environment, defaultConfigPath),
            DbCleanupCommand(runtime, environment, defaultConfigPath),
        ),
    )

/** Wires repository-backed local and distributed runtimes used by the executable. */
private fun createProductionSapphireCommandRuntime(): SapphireCommandRuntime {
    val maintenanceUseCaseFactory = SnapshotMaintenanceUseCaseFactory { connection ->
        TransactionalSnapshotMaintenanceUseCase(
            repository = ExposedSnapshotMaintenanceRepository(),
            transactionRunner = ExposedTransactionRunner(connection.database),
            storageMaintenance = createStorageMaintenanceOperation(connection),
        )
    }
    return ProductionSapphireCommandRuntime(
        snapshotUseCaseFactory = SnapshotUseCaseFactory { connection ->
            TransactionalSnapshotUseCase(
                repository = ExposedDiskSnapshotRepository(),
                transactionRunner = ExposedTransactionRunner(connection.database),
            )
        },
        snapshotMaintenanceUseCaseFactory = maintenanceUseCaseFactory,
        distributedRuntime = ProductionDistributedCommandRuntime(maintenanceUseCaseFactory),
    )
}

/** Shared config loading and validation boundary for commands with TOML support. */
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

    /** Resolves oneshot settings and translates config failures into CLI usage errors. */
    protected fun resolveOneshotConfig(overrides: AgentConfigOverrides): EffectiveOneshotConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveOneshot(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves standalone settings, including periodic maintenance policy. */
    protected fun resolveStandaloneConfig(overrides: AgentConfigOverrides): EffectiveStandaloneConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveStandalone(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves storage for schema-only migration. */
    protected fun resolveDbMigrateConfig(overrides: AgentConfigOverrides): EffectiveDbMigrateConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveDbMigrate(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves explicit retention cleanup settings. */
    protected fun resolveDbCleanupConfig(overrides: AgentConfigOverrides): EffectiveDbCleanupConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveDbCleanup(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves Hub settings and enforces central-storage requirements. */
    protected fun resolveHubConfig(overrides: AgentConfigOverrides): EffectiveHubConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveHub(
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves endpoint and storage according to the token host mode. */
    protected fun resolveBootstrapTokenConfig(
        hostMode: BootstrapTokenHostMode,
        overrides: AgentConfigOverrides,
    ): EffectiveBootstrapTokenConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveBootstrapToken(
                hostMode = hostMode,
                config = loadConfig(),
                environment = environment,
                cli = overrides,
            )
        }
    }

    /** Resolves continuous node-agent delivery and local-console settings. */
    protected fun resolveNodeAgentConfig(overrides: AgentConfigOverrides): EffectiveNodeAgentConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveNodeAgent(loadConfig(), environment, overrides)
        }
    }

    /** Resolves one-time join transport settings. */
    protected fun resolveNodeAgentJoinConfig(overrides: AgentConfigOverrides): EffectiveNodeAgentJoinConfig {
        return resolveUsageErrors {
            AgentConfigResolver.resolveNodeAgentJoin(loadConfig(), environment, overrides)
        }
    }
    /** Converts domain config exceptions into Clikt's actionable usage failures. */
    private fun <T> resolveUsageErrors(block: () -> T): T {
        return try {
            block()
        } catch (error: AgentConfigParseException) {
            throw UsageError(error.message ?: "Invalid config file.")
        } catch (error: AgentConfigValidationException) {
            throw UsageError(error.message ?: "Invalid runtime configuration.")
        }
    }

    /** Loads the explicitly selected file or the installed default, if present. */
    private fun loadConfig(): AgentConfig {
        return AgentConfigLoader.load(configPath, defaultConfigPath)
    }
}

/** Adds scan/device options whose resolver enforces mutual exclusion for collection modes. */
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

    /** Preserves omission versus explicit scan/device choices for precedence resolution. */
    protected fun deviceOverrides(): AgentConfigOverrides {
        return AgentConfigOverrides(
            scan = scan,
            device = device?.absolutePathString(),
        )
    }
}

/** Implements one collection and optional persistence, then exits. */
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

    private val healthPolicy: String? by option("--health-policy")
        .help("Health policy for output (available: default)")

    override fun run() {
        val effective = resolveOneshotConfig(
            deviceOverrides().copy(
                outputMode = output,
                persist = persist,
                jdbcUrl = dbUrl,
                healthPolicy = healthPolicy,
            ),
        )

        runtime.run(
            SapphireCommandRequest.Oneshot(
                target = effective.target,
                outputMode = effective.outputMode,
                persist = effective.persist,
                storage = effective.storage,
                deviceIdentityNamespaceSalt = effective.deviceIdentityNamespaceSalt,
                healthPolicy = effective.healthPolicy,
            ),
        )
    }
}

/** Implements local API mode with periodic collection and retention maintenance. */
private class StandaloneCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : DeviceCommand("standalone", runtime, environment, defaultConfigPath) {
    override val invokeWithoutSubcommand: Boolean = true

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

    private val healthPolicy: String? by option("--health-policy")
        .help("Health policy for Standalone API evaluation and console output (available: default)")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val effective = resolveStandaloneConfig(
            deviceOverrides().copy(
                outputMode = output,
                intervalSeconds = intervalSeconds,
                host = host,
                port = port,
                jdbcUrl = dbUrl,
                healthPolicy = healthPolicy,
            ),
        )

        runtime.run(
            SapphireCommandRequest.Standalone(
                target = effective.target,
                outputMode = effective.outputMode,
                intervalSeconds = effective.intervalSeconds,
                host = effective.host,
                port = effective.port,
                storage = effective.storage,
                deviceIdentityNamespaceSalt = effective.deviceIdentityNamespaceSalt,
                rawSnapshotDays = effective.rawSnapshotDays,
                cleanupOnStartup = effective.cleanupOnStartup,
                cleanupIntervalHours = effective.cleanupIntervalHours,
                vacuumAfterCleanup = effective.vacuumAfterCleanup,
                healthPolicy = effective.healthPolicy,
            ),
        )
    }
}

/** Implements central API mode; all snapshots arrive from node agents. */
private class HubCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : ConfiguredCommand("hub", runtime, environment, defaultConfigPath) {
    override val invokeWithoutSubcommand: Boolean = true

    private val host: String? by option("--host")
        .help("Internal HTTP listen host")

    private val port: Int? by option("--port")
        .int()
        .help("Internal HTTP listen port")

    private val publicEndpoint: String? by option("--public-endpoint")
        .help("Operator-facing absolute HTTP(S) endpoint")

    private val allowInsecureTransport: Boolean? by option("--allow-insecure-transport")
        .nullableFlag("--require-https")
        .help("Explicitly allow an HTTP public endpoint")

    private val dbUrl: String? by option("--db-url")
        .help("Required Hub JDBC URL")

    private val nonceTtlSeconds: Long? by option("--nonce-ttl-seconds")
        .long()
        .help("Signed request nonce lifetime")

    private val maxRequestBodyBytes: Long? by option("--max-request-body-bytes")
        .long()
        .help("Maximum authenticated request body size")

    private val healthPolicy: String? by option("--health-policy")
        .help("Health policy for Hub API evaluation (available: default)")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val effective = resolveHubConfig(
            AgentConfigOverrides(
                host = host,
                port = port,
                publicEndpointBaseUrl = publicEndpoint,
                publicEndpointAllowInsecureTransport = allowInsecureTransport,
                jdbcUrl = dbUrl,
                nonceTtlSeconds = nonceTtlSeconds,
                maxRequestBodyBytes = maxRequestBodyBytes,
                healthPolicy = healthPolicy,
            ),
        )
        runtime.run(
            SapphireCommandRequest.Hub(
                host = effective.host,
                port = effective.port,
                publicEndpointBaseUrl = effective.publicEndpointBaseUrl,
                storage = effective.storage,
                nonceTtlSeconds = effective.nonceTtlSeconds,
                maxRequestBodyBytes = effective.maxRequestBodyBytes,
                rawSnapshotDays = effective.rawSnapshotDays,
                cleanupOnStartup = effective.cleanupOnStartup,
                cleanupIntervalHours = effective.cleanupIntervalHours,
                vacuumAfterCleanup = effective.vacuumAfterCleanup,
                healthPolicy = effective.healthPolicy,
            ),
        )
    }
}

/** Groups token creation commands without owning runtime behavior itself. */
private class BootstrapTokenGroup(name: String) : CliktCommand(name = name) {
    override fun run() = Unit
}

/** Groups principal administration commands. */
private class PrincipalGroup : CliktCommand(name = "principal") {
    override fun run() = Unit
}

/** Disables a persisted principal by its authenticated key id. */
private class PrincipalDisableCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : ConfiguredCommand("disable", runtime, environment, defaultConfigPath) {
    private val kid: String by option("--kid")
        .help("Principal key id to disable")
        .required()
        .validate {
            require(runCatching { Base64Url.decodeExact(it, 32, "kid") }.isSuccess) {
                "KID must be an unpadded base64url-encoded 32-byte value"
            }
        }

    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL containing the principal registry")

    override fun run() {
        val effective = resolveDbMigrateConfig(
            AgentConfigOverrides(jdbcUrl = dbUrl),
        )
        runtime.run(
            SapphireCommandRequest.PrincipalDisable(
                storage = effective.storage,
                kid = kid,
            ),
        )
    }
}

/** Validates and issues one-time pairing or join material for the selected host mode. */
private class BootstrapTokenCreateCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
    private val tokenType: BootstrapTokenType,
    private val hostMode: BootstrapTokenHostMode,
) : ConfiguredCommand("create", runtime, environment, defaultConfigPath) {
    private val expectedDisplayName: String? by option(
        if (tokenType == BootstrapTokenType.JOIN_TOKEN) "--node-name" else "--display-name",
    ).help("Optional display name restriction")

    private val ttlSeconds: Long? by option("--ttl-seconds")
        .long()
        .help("Bootstrap token lifetime")
        .validate { require(it in 10..3600) { "TTL must be between 10 and 3600 seconds" } }

    private val recoveryNodeId: String? by option("--recovery-node-id")
        .help("Existing node UUID for credential recovery")

    private val publicEndpoint: String? by option("--public-endpoint")
        .help("Operator-facing absolute HTTP(S) endpoint")

    private val allowInsecureTransport: Boolean? by option("--allow-insecure-transport")
        .nullableFlag("--require-https")
        .help("Explicitly allow an HTTP public endpoint")

    private val dbUrl: String? by option("--db-url")
        .help(
            if (hostMode == BootstrapTokenHostMode.HUB) {
                "Required Hub JDBC URL"
            } else {
                "Standalone history JDBC URL"
            },
        )

    override fun run() {
        if (tokenType != BootstrapTokenType.JOIN_TOKEN && recoveryNodeId != null) {
            throw UsageError("--recovery-node-id is available only for join tokens.")
        }
        recoveryNodeId?.let { value ->
            runCatching { java.util.UUID.fromString(value) }
                .getOrElse { throw UsageError("--recovery-node-id must be a UUID.") }
        }
        val effective = resolveBootstrapTokenConfig(
            hostMode = hostMode,
            overrides = AgentConfigOverrides(
                jdbcUrl = dbUrl,
                publicEndpointBaseUrl = publicEndpoint,
                publicEndpointAllowInsecureTransport = allowInsecureTransport,
            ),
        )
        runtime.run(
            SapphireCommandRequest.BootstrapTokenCreate(
                tokenType = tokenType,
                storage = effective.storage,
                publicEndpointBaseUrl = effective.publicEndpointBaseUrl,
                ttlSeconds = ttlSeconds ?: BootstrapTokenService.DEFAULT_TTL.inWholeSeconds,
                expectedDisplayName = expectedDisplayName,
                recoveryNodeId = recoveryNodeId,
            ),
        )
    }
}

/** Runs continuous local collection with signed delivery and heartbeat reporting. */
private class NodeAgentCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : DeviceCommand("node-agent", runtime, environment, defaultConfigPath) {
    override val invokeWithoutSubcommand: Boolean = true

    private val intervalSeconds: Long? by option("--interval-seconds")
        .long()
        .help("Collection interval in seconds")

    private val output: OutputMode? by option("--output")
        .enum<OutputMode>(ignoreCase = true, key = { it.name })
        .help("Snapshot console output format")

    private val hubEndpoint: String? by option("--hub")
        .help("Hub absolute HTTP(S) endpoint")

    private val allowInsecureTransport: Boolean? by option("--allow-insecure-transport")
        .nullableFlag("--require-https")
        .help("Explicitly allow an HTTP Hub endpoint")

    private val credentialFile: String? by option("--credential-file")
        .help("Owner-only Node Agent credential JSON")

    private val pemCaFile: String? by option("--pem-ca-file")
        .help("Optional additional PEM CA for HTTPS")

    private val heartbeatIntervalSeconds: Long? by option("--heartbeat-interval-seconds")
        .long()
        .help("Heartbeat interval in seconds")

    private val requestTimeoutSeconds: Long? by option("--request-timeout-seconds")
        .long()
        .help("Hub request timeout in seconds")

    private val maxRetries: Int? by option("--max-retries")
        .int()
        .help("Immediate delivery retries after the first attempt")

    private val healthPolicy: String? by option("--health-policy")
        .help("Health policy for local console output only; does not affect Hub-side history evaluation (available: default)")

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        val effective = resolveNodeAgentConfig(
            deviceOverrides().copy(
                outputMode = output,
                intervalSeconds = intervalSeconds,
                hubEndpoint = hubEndpoint,
                hubAllowInsecureTransport = allowInsecureTransport,
                credentialFile = credentialFile,
                pemCaFile = pemCaFile,
                heartbeatIntervalSeconds = heartbeatIntervalSeconds,
                requestTimeoutSeconds = requestTimeoutSeconds,
                maxRetries = maxRetries,
                healthPolicy = healthPolicy,
            ),
        )
        runtime.run(
            SapphireCommandRequest.NodeAgent(
                target = effective.target,
                outputMode = effective.outputMode,
                intervalSeconds = effective.intervalSeconds,
                hubEndpoint = effective.hubEndpoint,
                hubAllowInsecureTransport = effective.hubAllowInsecureTransport,
                credentialFile = effective.credentialFile,
                pemCaFile = effective.pemCaFile,
                heartbeatIntervalSeconds = effective.heartbeatIntervalSeconds,
                requestTimeoutSeconds = effective.requestTimeoutSeconds,
                maxRetries = effective.maxRetries,
                deviceIdentityNamespaceSalt = effective.deviceIdentityNamespaceSalt,
                healthPolicy = effective.healthPolicy,
            ),
        )
    }
}

/** Performs the one-time join exchange and writes an owner-only node credential. */
private class NodeAgentJoinCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
) : ConfiguredCommand("join", runtime, environment, defaultConfigPath) {
    private val hubEndpoint: String? by option("--hub")
        .help("Hub absolute HTTP(S) endpoint")

    private val allowInsecureTransport: Boolean? by option("--allow-insecure-transport")
        .nullableFlag("--require-https")
        .help("Explicitly allow an HTTP Hub endpoint")

    private val credentialFile: String? by option("--credential-file")
        .help("Owner-only Node Agent credential JSON to create")

    private val pemCaFile: String? by option("--pem-ca-file")
        .help("Optional additional PEM CA for HTTPS")

    private val requestTimeoutSeconds: Long? by option("--request-timeout-seconds")
        .long()
        .help("Hub request timeout in seconds")

    private val hubId: String by option("--hub-id")
        .help("Persistent Hub id from the join material")
        .required()

    private val tokenId: String by option("--token-id")
        .help("Join token id")
        .required()

    private val tokenSecret: String by option("--token-secret")
        .help("One-time join token secret")
        .required()

    private val nodeName: String by option("--node-name")
        .help("Node display name")
        .required()

    private val intervalSeconds: Long? by option("--interval-seconds")
        .long()
        .help("Expected collection interval in seconds")

    override fun run() {
        if (nodeName.isBlank()) throw UsageError("--node-name must not be blank.")
        val effective = resolveNodeAgentJoinConfig(
            AgentConfigOverrides(
                hubEndpoint = hubEndpoint,
                hubAllowInsecureTransport = allowInsecureTransport,
                credentialFile = credentialFile,
                pemCaFile = pemCaFile,
                requestTimeoutSeconds = requestTimeoutSeconds,
            ),
        )
        val expectedInterval = intervalSeconds ?: AgentConfigDefaults.COLLECTION_INTERVAL_SECONDS
        if (expectedInterval <= 0) throw UsageError("--interval-seconds must be greater than zero.")
        runtime.run(
            SapphireCommandRequest.NodeAgentJoin(
                hubEndpoint = effective.hubEndpoint,
                hubAllowInsecureTransport = effective.hubAllowInsecureTransport,
                credentialFile = effective.credentialFile,
                pemCaFile = effective.pemCaFile,
                requestTimeoutSeconds = effective.requestTimeoutSeconds,
                hubId = hubId,
                joinTokenId = tokenId,
                joinTokenSecret = tokenSecret,
                nodeName = nodeName,
                expectedCollectionIntervalSeconds = expectedInterval,
            ),
        )
    }
}

/** Groups explicit database schema and retention operations. */
private class DbCommand : CliktCommand(name = "db") {
    override fun run() = Unit
}

/** Applies schema migrations without collecting or starting an HTTP server. */
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
                storage = effective.storage,
            ),
        )
    }
}

/** Executes retention cleanup with optional dry-run and backend vacuum. */
private class DbCleanupCommand(
    runtime: SapphireCommandRuntime,
    environment: Map<String, String>,
    defaultConfigPath: Path?,
): ConfiguredCommand("cleanup", runtime, environment, defaultConfigPath) {
    private val dbUrl: String? by option("--db-url")
        .help("JDBC URL for local history storage")

    private val rawSnapshotDays: Int? by option("--raw-snapshot-days")
        .int()
        .help("Raw snapshot retention in days")
        .validate { require(it in 1..365) { "Raw snapshot days must be between 1 and 365" } }

    private val vacuum: Boolean? by option("--vacuum")
        .nullableFlag("--no-vacuum")
        .help("Run backend vacuum after cleanup")

    private val dryRun: Boolean by option("--dry-run")
        .flag(default = false)
        .help("Show cleanup targets without deleting snapshots")

    override fun run() {
        val effective = resolveDbCleanupConfig(
            AgentConfigOverrides(
                jdbcUrl = dbUrl,
                rawSnapshotDays = rawSnapshotDays,
                vacuumAfterCleanup = vacuum,
            ),
        )

        runtime.run(
            SapphireCommandRequest.DbCleanup(
                storage = effective.storage,
                rawSnapshotDays = effective.rawSnapshotDays,
                dryRun = dryRun,
                vacuumAfterCleanup = effective.vacuumAfterCleanup,
            ),
        )
    }
}

/** Process entry point; Clikt owns argument errors and command dispatch. */
fun main(args: Array<String>) = createSapphireAgentCommand().main(args)
