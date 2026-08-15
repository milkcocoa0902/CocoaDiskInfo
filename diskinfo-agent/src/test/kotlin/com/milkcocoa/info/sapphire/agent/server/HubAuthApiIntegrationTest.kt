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
import com.milkcocoa.info.sapphire.agent.usecase.HubHeartbeatUseCase
import com.milkcocoa.info.sapphire.agent.usecase.HubLatestSnapshotsQueryService
import com.milkcocoa.info.sapphire.agent.usecase.HubSnapshotIngestUseCase
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequestCodec
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthErrorCodes
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import com.milkcocoa.info.sapphire.core.auth.SnapshotBodyDigest
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val HubApiTestJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

@OptIn(ExperimentalUuidApi::class)
class HubAuthApiIntegrationTest {
    @Test
    fun `join and signed ingest are idempotent while a replayed nonce is rejected`() {
        val databaseFile = Files.createTempFile("hub-auth-api", ".db")
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")
        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            val transactions = ExposedTransactionRunner(connection.database)
            val hubIdentities = ExposedHubIdentityRepository()
            val principals = ExposedSecurityPrincipalRepository()
            val tokens = ExposedBootstrapTokenRepository()
            val registry = ExposedNodeAgentRegistryRepository()
            val snapshots = ExposedDiskSnapshotRepository()
            val nonces = InMemoryNonceStore()
            val fixedNow = Instant.parse("2026-08-15T00:00:00Z")
            val hubId = Uuid.parse("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

            val (token, pairingToken) = runBlocking {
                transactions.readWrite {
                    hubIdentities.getOrCreate(HubIdentity(hubId, fixedNow))
                }
                val tokenService = BootstrapTokenService(
                    repository = tokens,
                    transactionRunner = transactions,
                    now = { fixedNow },
                )
                tokenService.issue(
                    type = BootstrapTokenType.JOIN_TOKEN,
                    expectedDisplayName = "node-a",
                ) to tokenService.issue(
                    type = BootstrapTokenType.PAIRING_TOKEN,
                    expectedDisplayName = "desktop-a",
                )
            }
            val verifier = SignedRequestVerifier(principals, transactions, nonces)
            val dependencies = HubAuthApiDependencies(
                nonceIssuer = AuthorizedNonceIssuer(tokens, principals, transactions, nonces) { fixedNow },
                registrationService = BootstrapRegistrationService(
                    hubIdentityRepository = hubIdentities,
                    tokenRepository = tokens,
                    principalRepository = principals,
                    nodeRegistryRepository = registry,
                    transactionRunner = transactions,
                    nonceStore = nonces,
                    now = { fixedNow },
                ),
                signedRequestVerifier = verifier,
                snapshotIngestUseCase = HubSnapshotIngestUseCase(snapshots, registry, transactions),
                heartbeatUseCase = HubHeartbeatUseCase(registry, transactions),
                nonceTtl = 60.seconds,
                maximumRequestBodyBytes = 2 * 1024 * 1024,
                clock = Clock.fixed(fixedNow, ZoneOffset.UTC),
            )
            val keyPair = Ed25519Keys.generate()
            val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
            val kid = Ed25519Keys.kid(publicJwk)

            testApplication {
                application {
                    install(ContentNegotiation) { json(HubApiTestJson) }
                    installHubAuthApi(dependencies)
                    installHubReadApi(
                        HubReadApiDependencies(
                            queryService = HubLatestSnapshotsQueryService(snapshots, registry, transactions),
                            signedRequestVerifier = verifier,
                            nonceTtl = 60.seconds,
                        ),
                    )
                }

                val joinNonce = issueNonce(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.JOIN_TOKEN,
                        subjectId = token.token.tokenId.toString(),
                        purpose = AuthPurpose.JOIN,
                    ),
                )
                val joinInput = JoinProof.canonicalInput(
                    hubId = hubId.toString(),
                    joinTokenId = token.token.tokenId.toString(),
                    nonce = joinNonce,
                    kid = kid,
                    nodeName = "node-a",
                )
                val joinRequest = NodeJoinRequest(
                    joinTokenId = token.token.tokenId.toString(),
                    nonce = joinNonce,
                    publicKey = publicJwk,
                    kid = kid,
                    nodeName = "node-a",
                    expectedCollectionIntervalSeconds = 60,
                    proof = Base64Url.encode(
                        JoinProof.hmac(
                            JoinProof.deriveJoinKey(Base64Url.decode(token.secret)),
                            joinInput,
                        ),
                    ),
                )
                val joinResponse = client.post(CocoaAuthProtocol.NODE_JOIN_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(HubApiTestJson.encodeToString(joinRequest))
                }
                assertEquals(HttpStatusCode.OK, joinResponse.status, joinResponse.bodyAsText())
                val joined = HubApiTestJson.decodeFromString<NodeJoinResponse>(joinResponse.bodyAsText())
                assertEquals(kid, joined.kid)

                val clientKeyPair = Ed25519Keys.generate()
                val clientPublicJwk = Ed25519Keys.publicJwk(clientKeyPair.public)
                val clientKid = Ed25519Keys.kid(clientPublicJwk)
                val pairingNonce = issueNonce(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PAIRING_TOKEN,
                        subjectId = pairingToken.token.tokenId.toString(),
                        purpose = AuthPurpose.CLIENT_PAIR,
                    ),
                )
                val pairInput = JoinProof.canonicalInput(
                    hubId = hubId.toString(),
                    joinTokenId = pairingToken.token.tokenId.toString(),
                    nonce = pairingNonce,
                    kid = clientKid,
                    nodeName = "desktop-a",
                )
                val pairResponse = client.post(CocoaAuthProtocol.CLIENT_PAIR_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        HubApiTestJson.encodeToString(
                            ClientPairRequest(
                                pairingTokenId = pairingToken.token.tokenId.toString(),
                                nonce = pairingNonce,
                                publicKey = clientPublicJwk,
                                kid = clientKid,
                                displayName = "desktop-a",
                                proof = Base64Url.encode(
                                    JoinProof.hmac(
                                        JoinProof.deriveJoinKey(Base64Url.decode(pairingToken.secret)),
                                        pairInput,
                                    ),
                                ),
                            ),
                        ),
                    )
                }
                assertEquals(HttpStatusCode.OK, pairResponse.status, pairResponse.bodyAsText())

                val ingestId = Uuid.parse("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                val ingestBody = HubApiTestJson.encodeToString(
                    SnapshotIngestRequest(
                        ingestId = ingestId.toString(),
                        snapshot = testDiskSnapshot(
                            deviceKey = "c25b5b07-5629-5c33-89ba-1ef17c03cc0c",
                            timestampMillis = 1_000,
                        ),
                    ),
                ).encodeToByteArray()
                val ingestNonce = issueNonce(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PRINCIPAL,
                        subjectId = kid,
                        purpose = AuthPurpose.SNAPSHOT_INGEST,
                    ),
                )

                val stored = signedIngest(ingestBody, ingestNonce, keyPair.private, kid)
                assertEquals(HttpStatusCode.OK, stored.status, stored.bodyAsText())
                assertEquals(
                    SnapshotIngestStatus.STORED,
                    HubApiTestJson.decodeFromString<SnapshotIngestResponse>(stored.bodyAsText()).status,
                )
                assertNotNull(stored.headers[CocoaAuthProtocol.NEXT_NONCE_HEADER])

                val replay = signedIngest(ingestBody, ingestNonce, keyPair.private, kid)
                assertEquals(HttpStatusCode.Unauthorized, replay.status)
                assertEquals(
                    CocoaAuthErrorCodes.NONCE_UNAVAILABLE,
                    HubApiTestJson.decodeFromString<HubFailureResponse>(replay.bodyAsText()).error.code,
                )

                val retryNonce = issueNonce(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PRINCIPAL,
                        subjectId = kid,
                        purpose = AuthPurpose.SNAPSHOT_INGEST,
                    ),
                )
                val duplicate = signedIngest(ingestBody, retryNonce, keyPair.private, kid)
                assertEquals(HttpStatusCode.OK, duplicate.status, duplicate.bodyAsText())
                assertEquals(
                    SnapshotIngestStatus.DUPLICATE,
                    HubApiTestJson.decodeFromString<SnapshotIngestResponse>(duplicate.bodyAsText()).status,
                )

                val readNonce = issueNonce(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PRINCIPAL,
                        subjectId = clientKid,
                        purpose = AuthPurpose.CLIENT_READ,
                    ),
                )
                val query = CanonicalRequestCodec.query(mapOf("cursor" to null, "limit" to "100"))
                val readProof = SignedRequestJws.sign(
                    CanonicalRequest(
                        method = "GET",
                        path = "/api/v1/snapshots/latest",
                        query = query,
                        purpose = AuthPurpose.CLIENT_READ,
                    ).signedPayload(readNonce),
                    clientKeyPair.private,
                    clientKid,
                )
                val latest = client.get("/api/v1/snapshots/latest?limit=100") {
                    header(
                        CocoaAuthProtocol.AUTHORIZATION_HEADER,
                        CocoaAuthorization.format(readProof),
                    )
                }
                assertEquals(HttpStatusCode.OK, latest.status, latest.bodyAsText())
                val latestPayload = HubApiTestJson.decodeFromString<LatestResponse>(latest.bodyAsText()).payload
                assertEquals(joined.nodeId, latestPayload.nodes.single().nodeId)
                assertEquals(1, latestPayload.nodes.single().devices.size)

                val boundaryNonce = checkNotNull(latest.headers[CocoaAuthProtocol.NEXT_NONCE_HEADER])
                val boundaryProof = SignedRequestJws.sign(
                    CanonicalRequest(
                        method = "GET",
                        path = "/api/v1/snapshots/latest",
                        query = query,
                        purpose = AuthPurpose.CLIENT_READ,
                    ).signedPayload(boundaryNonce),
                    clientKeyPair.private,
                    clientKid,
                )
                val invalidLimit = client.get("/api/v1/snapshots/latest?limit=not-a-number") {
                    header(
                        CocoaAuthProtocol.AUTHORIZATION_HEADER,
                        CocoaAuthorization.format(boundaryProof),
                    )
                }
                assertEquals(HttpStatusCode.BadRequest, invalidLimit.status)
                assertEquals(
                    "latest_limit_invalid",
                    HubApiTestJson.decodeFromString<HubFailureResponse>(invalidLimit.bodyAsText()).error.code,
                )
                val validAfterInvalidQuery = client.get("/api/v1/snapshots/latest?limit=100") {
                    header(
                        CocoaAuthProtocol.AUTHORIZATION_HEADER,
                        CocoaAuthorization.format(boundaryProof),
                    )
                }
                assertEquals(HttpStatusCode.OK, validAfterInvalidQuery.status, validAfterInvalidQuery.bodyAsText())

                val digestBoundaryNonce = checkNotNull(
                    validAfterInvalidQuery.headers[CocoaAuthProtocol.NEXT_NONCE_HEADER],
                )
                val digestBoundaryProof = SignedRequestJws.sign(
                    CanonicalRequest(
                        method = "GET",
                        path = "/api/v1/snapshots/latest",
                        query = query,
                        purpose = AuthPurpose.CLIENT_READ,
                    ).signedPayload(digestBoundaryNonce),
                    clientKeyPair.private,
                    clientKid,
                )
                val bodyDigestOnGet = client.get("/api/v1/snapshots/latest?limit=100") {
                    header(
                        CocoaAuthProtocol.AUTHORIZATION_HEADER,
                        CocoaAuthorization.format(digestBoundaryProof),
                    )
                    header(
                        CocoaAuthProtocol.CONTENT_DIGEST_HEADER,
                        SnapshotBodyDigest.contentDigest(ByteArray(0)),
                    )
                }
                assertEquals(HttpStatusCode.Unauthorized, bodyDigestOnGet.status)
                assertEquals(
                    "authentication_invalid",
                    HubApiTestJson.decodeFromString<HubFailureResponse>(bodyDigestOnGet.bodyAsText()).error.code,
                )
                val validAfterRejectedDigest = client.get("/api/v1/snapshots/latest?limit=100") {
                    header(
                        CocoaAuthProtocol.AUTHORIZATION_HEADER,
                        CocoaAuthorization.format(digestBoundaryProof),
                    )
                }
                assertEquals(
                    HttpStatusCode.OK,
                    validAfterRejectedDigest.status,
                    validAfterRejectedDigest.bodyAsText(),
                )
            }
        }
    }
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.issueNonce(
    request: NonceIssueRequest,
): String {
    val response = client.post(CocoaAuthProtocol.NONCE_PATH) {
        contentType(ContentType.Application.Json)
        setBody(HubApiTestJson.encodeToString(request))
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return HubApiTestJson.decodeFromString<NonceIssueResponse>(response.bodyAsText()).nonce
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.signedIngest(
    body: ByteArray,
    nonce: String,
    privateKey: java.security.PrivateKey,
    kid: String,
) = client.post(CocoaAuthProtocol.SNAPSHOT_INGEST_PATH) {
    val canonical = CanonicalRequest(
        method = "POST",
        path = CocoaAuthProtocol.SNAPSHOT_INGEST_PATH,
        query = "",
        purpose = AuthPurpose.SNAPSHOT_INGEST,
        bodySha256 = SnapshotBodyDigest.bodySha256(body),
    )
    header(
        CocoaAuthProtocol.AUTHORIZATION_HEADER,
        CocoaAuthorization.format(SignedRequestJws.sign(canonical.signedPayload(nonce), privateKey, kid)),
    )
    header(CocoaAuthProtocol.CONTENT_DIGEST_HEADER, SnapshotBodyDigest.contentDigest(body))
    contentType(ContentType.Application.Json)
    setBody(body)
}

@Serializable
private data class HubFailureResponse(
    val error: ApiError,
)

@Serializable
private data class LatestResponse(
    val payload: LatestSnapshotsPayload,
)
