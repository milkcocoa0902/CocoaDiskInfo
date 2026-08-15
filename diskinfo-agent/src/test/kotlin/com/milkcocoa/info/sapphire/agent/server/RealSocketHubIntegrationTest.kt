package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.auth.AuthorizedNonceIssuer
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationService
import com.milkcocoa.info.sapphire.agent.auth.BootstrapTokenService
import com.milkcocoa.info.sapphire.agent.auth.InMemoryNonceStore
import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.ExposedBootstrapTokenRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedDiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedHubIdentityRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedNodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedSecurityPrincipalRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.HubIdentity
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentConnection
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentJoinClient
import com.milkcocoa.info.sapphire.agent.transport.NodeAgentJoinCommand
import com.milkcocoa.info.sapphire.agent.transport.NodeDeliveryRetryPolicy
import com.milkcocoa.info.sapphire.agent.transport.SignedNodeAgentTransport
import com.milkcocoa.info.sapphire.agent.usecase.HubHeartbeatUseCase
import com.milkcocoa.info.sapphire.agent.usecase.HubSnapshotIngestUseCase
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class RealSocketHubIntegrationTest {
    @Test
    fun `node joins and retries an idempotent signed ingest over a real HTTP socket`() = runBlocking {
        val databaseFile = Files.createTempFile("hub-real-socket", ".db")
        val credentialDirectory = Files.createTempDirectory("hub-real-socket-credential")
        val credentialFile = credentialDirectory.resolve("node.json")
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")
        createStorageMigratorFactory().create(storage).migrate(storage)

        try {
            StorageConnectionFactory.connect(storage).use { connection ->
                val transactions = ExposedTransactionRunner(connection.database)
                val hubIdentities = ExposedHubIdentityRepository()
                val principals = ExposedSecurityPrincipalRepository()
                val tokens = ExposedBootstrapTokenRepository()
                val registry = ExposedNodeAgentRegistryRepository()
                val snapshots = ExposedDiskSnapshotRepository()
                val nonces = InMemoryNonceStore()
                val hubId = Uuid.random()

                transactions.readWrite {
                    hubIdentities.getOrCreate(HubIdentity(hubId, Clock.systemUTC().instant()))
                }
                val joinToken = BootstrapTokenService(tokens, transactions).issue(
                    type = BootstrapTokenType.JOIN_TOKEN,
                    expectedDisplayName = "socket-node",
                )
                val verifier = SignedRequestVerifier(principals, transactions, nonces)
                val dependencies = HubAuthApiDependencies(
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
                    snapshotIngestUseCase = HubSnapshotIngestUseCase(snapshots, registry, transactions),
                    heartbeatUseCase = HubHeartbeatUseCase(registry, transactions),
                    nonceTtl = 60.seconds,
                    maximumRequestBodyBytes = 2 * 1024 * 1024,
                )
                val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                    install(ContentNegotiation) { json(Json) }
                    installHubAuthApi(dependencies)
                }.start(wait = false)

                try {
                    val port = server.engine.resolvedConnectors().single().port
                    val endpoint = "http://127.0.0.1:$port"
                    val connectionSettings = NodeAgentConnection(
                        endpoint = endpoint,
                        allowInsecureTransport = true,
                    )
                    val credential = NodeAgentJoinClient().join(
                        NodeAgentJoinCommand(
                            connection = connectionSettings,
                            hubId = hubId.toString(),
                            joinTokenId = joinToken.token.tokenId.toString(),
                            joinTokenSecret = joinToken.secret,
                            nodeName = "socket-node",
                            expectedCollectionIntervalSeconds = 60,
                            credentialPath = credentialFile,
                        ),
                    )
                    SignedNodeAgentTransport.create(
                        credential = credential,
                        connection = connectionSettings,
                        retryPolicy = NodeDeliveryRetryPolicy(maximumAttempts = 1),
                    ).use { transport ->
                        val request = SnapshotIngestRequest(
                            ingestId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                            snapshot = testDiskSnapshot(
                                deviceKey = "c25b5b07-5629-5c33-89ba-1ef17c03cc0c",
                                timestampMillis = 1_000,
                            ),
                        )

                        assertEquals(SnapshotIngestStatus.STORED, transport.ingest(request).status)
                        assertEquals(SnapshotIngestStatus.DUPLICATE, transport.ingest(request).status)
                    }
                } finally {
                    server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
                }
            }
        } finally {
            Files.deleteIfExists(credentialFile)
            Files.deleteIfExists(credentialDirectory)
            Files.deleteIfExists(databaseFile)
        }
    }
}
