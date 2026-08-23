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

internal sealed interface SapphireCommandRequest {
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

    data class BootstrapTokenCreate(
        val tokenType: BootstrapTokenType,
        val storage: StorageSettings,
        val publicEndpointBaseUrl: String,
        val ttlSeconds: Long,
        val expectedDisplayName: String?,
        val recoveryNodeId: String? = null,
    ) : SapphireCommandRequest

    data class PrincipalDisable(
        val storage: StorageSettings,
        val kid: String,
    ) : SapphireCommandRequest

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
    private val distributedRuntime: SapphireCommandRuntime? = null,
    private val schemaValidator: StorageSchemaValidator = FlywayStorageSchemaValidator,
) : SapphireCommandRuntime {
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

    private fun runExecutor(executor: SapphireExecutor) {
        executorRunner.run(executor)
    }

    private fun setupConsoleOutput(outputMode: OutputMode) {
        ColotokProviderFactory
            .create(outputMode = outputMode)
            .also { ColotokLoggerContext.setDefault(it) }
    }

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

    private fun createCollector(namespaceSalt: String): SmartctlCollector {
        return SmartctlCollector(
            deviceKeyDeriver = UuidV5DeviceKeyDeriver(namespaceSalt),
        )
    }

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
