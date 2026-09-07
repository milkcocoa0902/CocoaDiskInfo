package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.config.AgentConfigDefaults
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnection
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.ExposedBootstrapTokenRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedHubIdentityRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedNodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedSecurityPrincipalRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.FlywayStorageSchemaValidator
import com.milkcocoa.info.sapphire.agent.datastore.HubIdentity
import com.milkcocoa.info.sapphire.agent.datastore.StorageSchemaValidator
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import com.milkcocoa.info.sapphire.agent.identity.UuidV5DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.SapphireAgentServer
import com.milkcocoa.info.sapphire.agent.server.StandaloneAuthDependencies
import com.milkcocoa.info.sapphire.agent.auth.AuthorizedNonceIssuer
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationService
import com.milkcocoa.info.sapphire.agent.auth.InMemoryNonceStore
import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.CompositeSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.RepositorySnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.SnapshotSink
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import com.milkcocoa.info.sapphire.agent.usecase.StandaloneLatestSnapshotsQueryService
import com.milkcocoa.info.sapphire.core.health.DefaultHealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Command inputs passed from the CLI boundary to the appropriate runtime. */
internal sealed interface SapphireCommandRequest {
    /** One collection pass; persistence is enabled only when [persist] is true. */
    data class Oneshot(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val persist: Boolean,
        val storage: StorageSettings,
        val deviceIdentityNamespaceSalt: String,
        val healthPolicy: HealthPolicy = DefaultHealthPolicy,
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

    /** Long-lived local API and periodic collection/maintenance process. */
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
        val healthPolicy: HealthPolicy = DefaultHealthPolicy,
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

    /** Long-lived central API and maintenance process; it never collects disks locally. */
    data class Hub(
        val host: String,
        val port: Int,
        val publicEndpointBaseUrl: String,
        val storage: StorageSettings,
        val nonceTtlSeconds: Long,
        val maxRequestBodyBytes: Long,
        val rawSnapshotDays: Int,
        val cleanupOnStartup: Boolean,
        val cleanupIntervalHours: Long,
        val vacuumAfterCleanup: Boolean,
        val healthPolicy: HealthPolicy = DefaultHealthPolicy,
    ) : SapphireCommandRequest

    /** One-time bootstrap material issuance against Hub or standalone storage. */
    data class BootstrapTokenCreate(
        val tokenType: BootstrapTokenType,
        val storage: StorageSettings,
        val publicEndpointBaseUrl: String,
        val ttlSeconds: Long,
        val expectedDisplayName: String?,
        val recoveryNodeId: String? = null,
    ) : SapphireCommandRequest

    /** Disables an authenticated principal without deleting its audit identity. */
    data class PrincipalDisable(
        val storage: StorageSettings,
        val kid: String,
    ) : SapphireCommandRequest

    /** Continuous local collection and signed delivery to a Hub. */
    data class NodeAgent(
        val target: TargetDevice,
        val outputMode: OutputMode,
        val intervalSeconds: Long,
        val hubEndpoint: String,
        val hubAllowInsecureTransport: Boolean,
        val credentialFile: String,
        val pemCaFile: String?,
        val heartbeatIntervalSeconds: Long,
        val requestTimeoutSeconds: Long,
        val maxRetries: Int,
        val deviceIdentityNamespaceSalt: String,
        val healthPolicy: HealthPolicy = DefaultHealthPolicy,
    ) : SapphireCommandRequest

    /** One-time remote join that exchanges a bootstrap secret for node credentials. */
    data class NodeAgentJoin(
        val hubEndpoint: String,
        val hubAllowInsecureTransport: Boolean,
        val credentialFile: String,
        val pemCaFile: String?,
        val requestTimeoutSeconds: Long,
        val hubId: String,
        val joinTokenId: String,
        val joinTokenSecret: String,
        val nodeName: String,
        val expectedCollectionIntervalSeconds: Long,
    ) : SapphireCommandRequest

    /** Explicit schema operation; no collection or server lifecycle is started. */
    data class DbMigrate(
        val storage: StorageSettings,
    ) : SapphireCommandRequest {
        constructor(dbUrl: String) : this(storage = StorageSettings.fromJdbcUrl(dbUrl))

        val dbUrl: String
            get() = storage.jdbcUrl
    }

    /** Explicit retention operation with optional dry-run and vacuum behavior. */
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

/** Dispatches command requests while keeping command construction separate from execution. */
internal interface SapphireCommandRuntime {
    /** Runs the request or fails when its required distributed runtime is unavailable. */
    fun run(request: SapphireCommandRequest)
}

/** Creates a snapshot use case after a storage connection is opened. */
internal fun interface SnapshotUseCaseFactory {
    fun create(connection: StorageConnection): SnapshotUseCase
}

/** Creates retention operations bound to a storage connection. */
internal fun interface SnapshotMaintenanceUseCaseFactory {
    fun create(connection: StorageConnection): SnapshotMaintenanceUseCase
}

/** Abstracts connection acquisition so runtime behavior can be tested without global state. */
internal fun interface StorageConnectionProvider {
    fun connect(settings: StorageSettings): StorageConnection
}

/** Bridges suspending executors to the command runtime's synchronous API. */
internal fun interface SapphireExecutorRunner {
    fun run(executor: SapphireExecutor)
}

/**
 * Production dispatcher for local modes, delegating distributed modes to their runtime.
 * It opens storage only for modes that need it and validates schema before long-lived
 * standalone startup; the delegated distributed runtime applies the same boundary to Hub
 * startup, while migration remains an explicit operator action.
 */
internal class ProductionSapphireCommandRuntime(
    private val snapshotUseCaseFactory: SnapshotUseCaseFactory,
    private val snapshotMaintenanceUseCaseFactory: SnapshotMaintenanceUseCaseFactory,
    private val storageConnectionProvider: StorageConnectionProvider =
        StorageConnectionProvider(StorageConnectionFactory::connect),
    private val executorRunner: SapphireExecutorRunner = BlockingSapphireExecutorRunner,
    private val distributedRuntime: SapphireCommandRuntime? = null,
    private val schemaValidator: StorageSchemaValidator = FlywayStorageSchemaValidator,
) : SapphireCommandRuntime {
    /** Routes each request to its mode-specific lifecycle and resource ownership policy. */
    override fun run(request: SapphireCommandRequest) {
        when (request) {
            is SapphireCommandRequest.Oneshot -> runOneshot(request)
            is SapphireCommandRequest.Standalone -> runStandalone(request)
            is SapphireCommandRequest.Hub -> requireNotNull(distributedRuntime) {
                "Distributed runtime is not configured."
            }.run(request)
            is SapphireCommandRequest.BootstrapTokenCreate -> requireNotNull(distributedRuntime) {
                "Distributed runtime is not configured."
            }.run(request)
            is SapphireCommandRequest.PrincipalDisable -> requireNotNull(distributedRuntime) {
                "Distributed runtime is not configured."
            }.run(request)
            is SapphireCommandRequest.NodeAgent,
            is SapphireCommandRequest.NodeAgentJoin,
            -> requireNotNull(distributedRuntime) {
                "Distributed runtime is not configured."
            }.run(request)
            is SapphireCommandRequest.DbMigrate -> runExecutor(
                SapphireExecutor.Migrate(storage = request.storage),
            )
            is SapphireCommandRequest.DbCleanup -> runDbCleanup(request)
        }
    }

    /** Runs cleanup with a connection scoped to the operation, then closes it. */
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

    /** Installs console output and optionally scopes a persistence connection around oneshot. */
    private fun runOneshot(request: SapphireCommandRequest.Oneshot) {
        setupConsoleOutput(request.outputMode)
        logHealthPolicy(request.healthPolicy, authority = "console")

        if (request.persist) {
            storageConnectionProvider.connect(request.storage).use { connection ->
                val snapshotUseCase = snapshotUseCaseFactory.create(connection)

                runExecutor(
                    SapphireExecutor.Oneshot(
                        device = request.target,
                        collector = createCollector(request.deviceIdentityNamespaceSalt),
                        sink = createSnapshotSink(snapshotUseCase, request.healthPolicy),
                    ),
                )
            }
        } else {
            runExecutor(
                SapphireExecutor.Oneshot(
                    device = request.target,
                    collector = createCollector(request.deviceIdentityNamespaceSalt),
                    sink = createSnapshotSink(snapshotUseCase = null, healthPolicy = request.healthPolicy),
                ),
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    /** Validates schema, wires API/auth/history, and hands long-running work to the executor. */
    private fun runStandalone(request: SapphireCommandRequest.Standalone) {
        setupConsoleOutput(request.outputMode)
        logHealthPolicy(request.healthPolicy, authority = "standalone-api-and-console")
        // Standalone now owns authentication tables as well as snapshot history.
        // Validate before opening the runtime pool so an operator gets the same
        // explicit migration boundary as Hub startup.
        schemaValidator.requireCurrent(request.storage)
        storageConnectionProvider.connect(request.storage).use { connection ->
            val snapshotUseCase = snapshotUseCaseFactory.create(connection)
            val transactions = ExposedTransactionRunner(connection.database)
            val hubIdentities = ExposedHubIdentityRepository()
            val tokens = ExposedBootstrapTokenRepository()
            val principals = ExposedSecurityPrincipalRepository()
            val registry = ExposedNodeAgentRegistryRepository()
            val nonces = InMemoryNonceStore()
            runBlocking {
                transactions.readWrite {
                    hubIdentities.getOrCreate(HubIdentity(Uuid.random(), Instant.now()))
                }
            }
            val verifier = SignedRequestVerifier(principals, transactions, nonces)
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
                    sink = createSnapshotSink(snapshotUseCase, request.healthPolicy),
                    server = SapphireAgentServer(
                        snapshotUseCase = snapshotUseCase,
                        host = request.host,
                        port = request.port,
                        authDependencies = StandaloneAuthDependencies(
                            nonceIssuer = AuthorizedNonceIssuer(tokens, principals, transactions, nonces),
                            registrationService = BootstrapRegistrationService(
                                hubIdentityRepository = hubIdentities,
                                tokenRepository = tokens,
                                principalRepository = principals,
                                nodeRegistryRepository = registry,
                                transactionRunner = transactions,
                                nonceStore = nonces,
                            ),
                            signedRequestVerifier = verifier,
                            queryService = StandaloneLatestSnapshotsQueryService(
                                snapshotUseCase = snapshotUseCase,
                                healthPolicy = request.healthPolicy,
                            ),
                            snapshotUseCase = snapshotUseCase,
                            nonceTtl = AgentConfigDefaults.NONCE_TTL_SECONDS.seconds,
                            maximumRequestBodyBytes = AgentConfigDefaults.MAX_REQUEST_BODY_BYTES,
                            healthPolicy = request.healthPolicy,
                        ),
                    ),
                    maintenanceRunner = maintenanceRunner,
                    cleanupOnStartup = request.cleanupOnStartup,
                    cleanupInterval = request.cleanupIntervalHours.hours,
                ),
            )
        }
    }

    /** Invokes the configured runner; production uses a blocking coroutine boundary. */
    private fun runExecutor(executor: SapphireExecutor) {
        executorRunner.run(executor)
    }

    /** Installs the mode-specific logger context before collection can emit records. */
    private fun setupConsoleOutput(outputMode: OutputMode) {
        ColotokProviderFactory
            .create(outputMode = outputMode)
            .also { ColotokLoggerContext.setDefault(it) }
    }

    /** Records policy identity and the component authoritative for resulting evaluations. */
    private fun logHealthPolicy(healthPolicy: HealthPolicy, authority: String) {
        Colotok.info(
            msg = "Using health policy.",
            attr = mapOf(
                "policy_name" to healthPolicy.metadata.policyName,
                "policy_version" to healthPolicy.metadata.policyVersion.toString(),
                "policy_authority" to authority,
            ),
        )
    }

    /** Creates a collector whose device keys use the resolved identity namespace. */
    private fun createCollector(namespaceSalt: String): SmartctlCollector {
        return SmartctlCollector(
            deviceKeyDeriver = UuidV5DeviceKeyDeriver(namespaceSalt),
        )
    }

    /** Combines console output with repository persistence only when a use case is supplied. */
    private fun createSnapshotSink(
        snapshotUseCase: SnapshotUseCase?,
        healthPolicy: HealthPolicy = DefaultHealthPolicy,
    ): SnapshotSink {
        val outputSink = ColotokSnapshotSink(healthPolicy)
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

/** Runs an executor synchronously and always invokes process logger shutdown afterward. */
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
