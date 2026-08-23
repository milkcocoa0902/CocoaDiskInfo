package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.auth.AuthorizedNonceIssuer
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationService
import com.milkcocoa.info.sapphire.agent.auth.BootstrapTokenService
import com.milkcocoa.info.sapphire.agent.auth.InMemoryNonceStore
import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.datastore.ExposedBootstrapTokenRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedDiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedHubIdentityRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedNodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedSecurityPrincipalRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.FlywayStorageSchemaValidator
import com.milkcocoa.info.sapphire.agent.datastore.HubIdentity
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalStatus
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSchemaValidator
import com.milkcocoa.info.sapphire.agent.credential.OwnerOnlyJsonNodeAgentCredentialStore
import com.milkcocoa.info.sapphire.agent.exec.SapphireExecutor
import com.milkcocoa.info.sapphire.agent.exec.NodeAgentHeartbeat
import com.milkcocoa.info.sapphire.agent.identity.UuidV5DeviceKeyDeriver
import com.milkcocoa.info.sapphire.agent.collector.SmartctlCollector
import com.milkcocoa.info.sapphire.agent.maintenance.PeriodicMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.server.HubAuthApiDependencies
import com.milkcocoa.info.sapphire.agent.server.HubReadApiDependencies
import com.milkcocoa.info.sapphire.agent.server.BootstrapTokenMaterial
import com.milkcocoa.info.sapphire.agent.server.NodeHeartbeatRequest
import com.milkcocoa.info.sapphire.agent.server.NodeReportedError
import com.milkcocoa.info.sapphire.agent.sink.ColotokSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.CompositeSnapshotSink
import com.milkcocoa.info.sapphire.agent.sink.RemoteSnapshotSink
import com.milkcocoa.info.sapphire.agent.server.SapphireHubServer
import com.milkcocoa.info.sapphire.agent.usecase.HubHeartbeatUseCase
import com.milkcocoa.info.sapphire.agent.usecase.HubLatestSnapshotsQueryService
import com.milkcocoa.info.sapphire.agent.usecase.HubSnapshotIngestUseCase
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentConnection
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentJoinClient
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentJoinCommand
import com.milkcocoa.info.sapphire.agent.transport.NodeDeliveryRetryPolicy
import com.milkcocoa.info.sapphire.agent.transport.SignedNodeAgentTransport
import com.milkcocoa.info.colotok.core.logger.ColotokLoggerContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.nio.file.Path
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class ProductionDistributedCommandRuntime(
    private val snapshotMaintenanceUseCaseFactory: SnapshotMaintenanceUseCaseFactory,
    private val schemaValidator: StorageSchemaValidator = FlywayStorageSchemaValidator,
    private val now: () -> Instant = Instant::now,
) : SapphireCommandRuntime {
    override fun run(request: SapphireCommandRequest) {
        when (request) {
            is SapphireCommandRequest.Hub -> runHub(request)
            is SapphireCommandRequest.BootstrapTokenCreate -> createBootstrapToken(request)
            is SapphireCommandRequest.PrincipalDisable -> disablePrincipal(request)
            is SapphireCommandRequest.NodeAgentJoin -> joinNodeAgent(request)
            is SapphireCommandRequest.NodeAgent -> runNodeAgent(request)
            else -> error("Unsupported distributed command request: ${request::class.simpleName}")
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun disablePrincipal(request: SapphireCommandRequest.PrincipalDisable) {
        schemaValidator.requireCurrent(request.storage)
        StorageConnectionFactory.connect(request.storage).use { connection ->
            val transactions = ExposedTransactionRunner(connection.database)
            val principals = ExposedSecurityPrincipalRepository()
            val status = runBlocking {
                transactions.readWrite {
                    val principal = principals.findByKid(request.kid)
                        ?: throw IllegalArgumentException(
                            "No principal found for --kid '${request.kid}' in the configured storage.",
                        )
                    if (principal.status == PrincipalStatus.DISABLED) {
                        PrincipalDisableStatus.ALREADY_DISABLED
                    } else {
                        principals.disable(principal.principalId)
                        PrincipalDisableStatus.DISABLED
                    }
                }
            }
            val action = when (status) {
                PrincipalDisableStatus.DISABLED -> "disabled"
                PrincipalDisableStatus.ALREADY_DISABLED -> "already disabled"
            }
            println("Principal $action: kid=${request.kid}")
        }
    }

    private fun joinNodeAgent(request: SapphireCommandRequest.NodeAgentJoin) {
        val connection = request.toNodeAgentConnection()
        runBlocking {
            NodeAgentJoinClient().join(
                NodeAgentJoinCommand(
                    connection = connection,
                    hubId = request.hubId,
                    joinTokenId = request.joinTokenId,
                    joinTokenSecret = request.joinTokenSecret,
                    nodeName = request.nodeName,
                    expectedCollectionIntervalSeconds = request.expectedCollectionIntervalSeconds,
                    credentialPath = Path.of(request.credentialFile),
                ),
            )
        }
        println("Node Agent joined successfully. Credential saved to ${request.credentialFile}.")
    }

    private fun runNodeAgent(request: SapphireCommandRequest.NodeAgent) {
        ColotokProviderFactory.create(request.outputMode).also(ColotokLoggerContext::setDefault)
        Colotok.info(
            msg = "Using health policy.",
            attr = mapOf(
                "policy_name" to request.healthPolicy.metadata.policyName,
                "policy_version" to request.healthPolicy.metadata.policyVersion.toString(),
                "policy_authority" to "node-agent-console-only",
            ),
        )
        val credential = OwnerOnlyJsonNodeAgentCredentialStore().load(Path.of(request.credentialFile))
        val transport = SignedNodeAgentTransport.create(
            credential = credential,
            connection = request.toNodeAgentConnection(),
            retryPolicy = NodeDeliveryRetryPolicy(maximumAttempts = request.maxRetries + 1),
        )
        val remoteSink = RemoteSnapshotSink(transport)
        runBlocking {
            try {
                SapphireExecutor.NodeAgent(
                    device = request.target,
                    collectionInterval = request.intervalSeconds.seconds,
                    heartbeatInterval = request.heartbeatIntervalSeconds.seconds,
                    collector = SmartctlCollector(
                        deviceKeyDeriver = UuidV5DeviceKeyDeriver(request.deviceIdentityNamespaceSalt),
                    ),
                    sink = CompositeSnapshotSink(ColotokSnapshotSink(request.healthPolicy), remoteSink),
                    heartbeat = NodeAgentHeartbeat { failure ->
                        transport.heartbeat(
                            NodeHeartbeatRequest(
                                expectedCollectionIntervalSeconds = request.intervalSeconds,
                                error = failure?.let {
                                    NodeReportedError(
                                        code = "collection_or_delivery_failed",
                                        message = (it.message ?: it::class.simpleName.orEmpty()).take(1_024),
                                    )
                                },
                            ),
                        )
                    },
                ).execute()
            } finally {
                transport.close()
                Colotok.forceShutdown()
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun createBootstrapToken(request: SapphireCommandRequest.BootstrapTokenCreate) {
        schemaValidator.requireCurrent(request.storage)
        StorageConnectionFactory.connect(request.storage).use { connection ->
            val transactions = ExposedTransactionRunner(connection.database)
            val hubIdentities = ExposedHubIdentityRepository()
            val hubIdentity = runBlocking {
                transactions.readWrite {
                    hubIdentities.getOrCreate(HubIdentity(Uuid.random(), now()))
                }
            }
            val issued = runBlocking {
                BootstrapTokenService(
                    repository = ExposedBootstrapTokenRepository(),
                    transactionRunner = transactions,
                    now = now,
                ).issue(
                    type = request.tokenType,
                    ttl = request.ttlSeconds.seconds,
                    expectedDisplayName = request.expectedDisplayName,
                    recoveryNodeId = request.recoveryNodeId?.let(Uuid::parse),
                )
            }
            // This command is the only boundary that reveals the one-time secret.
            // A single JSON object is scriptable without copying it into normal logs.
            println(
                Json.encodeToString(
                    BootstrapTokenMaterial(
                        type = issued.token.tokenType.name,
                        endpoint = request.publicEndpointBaseUrl,
                        hubId = hubIdentity.hubId.toString(),
                        tokenId = issued.token.tokenId.toString(),
                        tokenSecret = issued.secret,
                        expiresAt = issued.token.expiresAt.toKotlinInstant(),
                    ),
                ),
            )
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun runHub(request: SapphireCommandRequest.Hub) {
        Colotok.info(
            msg = "Using health policy.",
            attr = mapOf(
                "policy_name" to request.healthPolicy.metadata.policyName,
                "policy_version" to request.healthPolicy.metadata.policyVersion.toString(),
                "policy_authority" to "hub-api",
            ),
        )
        // Hub never migrates implicitly. An operator-visible failure before the pool
        // and listener are created makes a schema mistake recoverable and predictable.
        schemaValidator.requireCurrent(request.storage)
        StorageConnectionFactory.connect(request.storage).use { connection ->
            val transactionRunner = ExposedTransactionRunner(connection.database)
            val hubIdentityRepository = ExposedHubIdentityRepository()
            val principalRepository = ExposedSecurityPrincipalRepository()
            val tokenRepository = ExposedBootstrapTokenRepository()
            val nodeRegistryRepository = ExposedNodeAgentRegistryRepository()
            val snapshotRepository = ExposedDiskSnapshotRepository()
            val nonceStore = InMemoryNonceStore()

            runBlocking {
                transactionRunner.readWrite {
                    hubIdentityRepository.getOrCreate(
                        HubIdentity(
                            hubId = Uuid.random(),
                            createdAt = now(),
                        ),
                    )
                }

                val verifier = SignedRequestVerifier(
                    principalRepository = principalRepository,
                    transactionRunner = transactionRunner,
                    nonceStore = nonceStore,
                )
                val server = SapphireHubServer(
                    dependencies = HubAuthApiDependencies(
                        nonceIssuer = AuthorizedNonceIssuer(
                            tokenRepository = tokenRepository,
                            principalRepository = principalRepository,
                            transactionRunner = transactionRunner,
                            nonceStore = nonceStore,
                            now = now,
                        ),
                        registrationService = BootstrapRegistrationService(
                            hubIdentityRepository = hubIdentityRepository,
                            tokenRepository = tokenRepository,
                            principalRepository = principalRepository,
                            nodeRegistryRepository = nodeRegistryRepository,
                            transactionRunner = transactionRunner,
                            nonceStore = nonceStore,
                            now = now,
                        ),
                        signedRequestVerifier = verifier,
                        snapshotIngestUseCase = HubSnapshotIngestUseCase(
                            snapshotRepository = snapshotRepository,
                            registryRepository = nodeRegistryRepository,
                            transactionRunner = transactionRunner,
                        ),
                        heartbeatUseCase = HubHeartbeatUseCase(
                            registryRepository = nodeRegistryRepository,
                            transactionRunner = transactionRunner,
                        ),
                        nonceTtl = request.nonceTtlSeconds.seconds,
                        maximumRequestBodyBytes = request.maxRequestBodyBytes,
                    ),
                    readDependencies = HubReadApiDependencies(
                        queryService = HubLatestSnapshotsQueryService(
                            snapshotRepository = snapshotRepository,
                            registryRepository = nodeRegistryRepository,
                            transactionRunner = transactionRunner,
                            healthPolicy = request.healthPolicy,
                        ),
                        signedRequestVerifier = verifier,
                        nonceTtl = request.nonceTtlSeconds.seconds,
                    ),
                    host = request.host,
                    port = request.port,
                )
                val maintenanceRunner = PeriodicMaintenanceRunner(
                    maintenanceUseCase = snapshotMaintenanceUseCaseFactory.create(connection),
                    rawSnapshotDays = request.rawSnapshotDays,
                    vacuumAfterCleanup = request.vacuumAfterCleanup,
                )
                try {
                    SapphireExecutor.Hub(
                        server = server,
                        maintenanceRunner = maintenanceRunner,
                        cleanupOnStartup = request.cleanupOnStartup,
                        cleanupInterval = request.cleanupIntervalHours.hours,
                    ).execute()
                } finally {
                    Colotok.forceShutdown()
                }
            }
        }
    }
}

private enum class PrincipalDisableStatus {
    DISABLED,
    ALREADY_DISABLED,
}

private fun SapphireCommandRequest.NodeAgent.toNodeAgentConnection(): NodeAgentConnection =
    NodeAgentConnection(
        endpoint = hubEndpoint,
        allowInsecureTransport = hubAllowInsecureTransport,
        pemCaPath = pemCaFile?.let(Path::of),
        requestTimeoutMillis = Math.multiplyExact(requestTimeoutSeconds, 1_000L),
    )

private fun SapphireCommandRequest.NodeAgentJoin.toNodeAgentConnection(): NodeAgentConnection =
    NodeAgentConnection(
        endpoint = hubEndpoint,
        allowInsecureTransport = hubAllowInsecureTransport,
        pemCaPath = pemCaFile?.let(Path::of),
        requestTimeoutMillis = Math.multiplyExact(requestTimeoutSeconds, 1_000L),
    )
