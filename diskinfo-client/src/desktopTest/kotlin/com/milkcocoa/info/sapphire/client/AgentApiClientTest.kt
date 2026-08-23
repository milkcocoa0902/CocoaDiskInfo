package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequestCodec
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class AgentApiClientTest {
    private val keyPair = Ed25519Keys.generate()
    private val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
    private val credential = ClientCredential(
        hubId = "hub-a",
        endpoint = "http://hub.local:14631",
        kid = Ed25519Keys.kid(publicJwk),
        privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
        publicKeyJwk = publicJwk,
        principalType = CredentialPrincipalType.CLIENT,
    )
    private val profile = ConnectionProfile(
        id = "hub-a",
        name = "Hub A",
        baseUrl = "http://hub.local:14631",
        allowInsecureTransport = true,
        credentialPath = "/credentials/client.json",
    )

    @Test
    fun `aggregate pages use signed canonical queries and next nonce`() = runBlocking {
        val issuedNonce = nonce(1)
        val nextNonce = nonce(2)
        val cursor = Base64Url.encode(byteArrayOf(1, 2, 3))
        var nonceRequests = 0
        var latestRequests = 0
        val client = clientWithHandler { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> {
                    nonceRequests += 1
                    respondJson(NonceIssueResponse(issuedNonce, Instant.DISTANT_FUTURE))
                }

                "/api/v1/snapshots/latest" -> {
                    val expectedCursor = if (latestRequests == 0) null else cursor
                    verifyReadProof(
                        request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
                        CanonicalRequest(
                            method = "GET",
                            path = "/api/v1/snapshots/latest",
                            query = CanonicalRequestCodec.query(
                                mapOf("cursor" to expectedCursor, "limit" to "100"),
                            ),
                            purpose = AuthPurpose.CLIENT_READ,
                        ),
                    )
                    val verifiedNonce = proofNonce(
                        request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
                    )
                    assertEquals(if (latestRequests == 0) issuedNonce else nextNonce, verifiedNonce)
                    val page = LatestSnapshotsPayload(
                        nodes = emptyList(),
                        pagination = if (latestRequests == 0) {
                            com.milkcocoa.info.sapphire.core.api.PageMetadata(100, cursor, hasMore = true)
                        } else {
                            com.milkcocoa.info.sapphire.core.api.PageMetadata(100)
                        },
                    )
                    latestRequests += 1
                    respondJson(
                        ApiResponse.Success(page),
                        additionalHeaders = if (latestRequests == 1) {
                            mapOf(CocoaAuthProtocol.NEXT_NONCE_HEADER to nextNonce)
                        } else {
                            emptyMap()
                        },
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val api = apiClient(client)

        val payload = api.fetchLatestSnapshots()

        assertEquals(2, latestRequests)
        assertEquals(1, nonceRequests)
        assertEquals(false, payload.pagination?.hasMore)
        api.close()
    }

    @Test
    fun `history signs effective values in canonical name order`() = runBlocking {
        val issuedNonce = nonce(3)
        val from = Instant.parse("2026-08-01T00:00:00Z")
        val to = Instant.parse("2026-08-02T00:00:00Z")
        val client = clientWithHandler { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(issuedNonce, Instant.DISTANT_FUTURE),
                )

                "/api/v1/nodes/node-a/devices/device-a/snapshots" -> {
                    verifyReadProof(
                        request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
                        CanonicalRequest(
                            method = "GET",
                            path = "/api/v1/nodes/node-a/devices/device-a/snapshots",
                            query = "from=2026-08-01T00%3A00%3A00Z&limit=25&order=asc&to=2026-08-02T00%3A00%3A00Z",
                            purpose = AuthPurpose.CLIENT_READ,
                        ),
                    )
                    respondJson(
                        ApiResponse.Success(
                            NodeDeviceHistoryPayload("node-a", "Node A", "device-a", emptyList()),
                        ),
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val api = apiClient(client)

        val payload = api.fetchDeviceHistory(
            nodeId = "node-a",
            deviceKey = "device-a",
            limit = 25,
            from = from,
            to = to,
            order = SnapshotHistoryOrder.ASC,
        )

        assertEquals("device-a", payload.deviceKey)
        api.close()
    }

    @Test
    fun `nonce unavailable is reissued and retried once`() = runBlocking {
        val nonces = listOf(nonce(4), nonce(5))
        var nonceRequests = 0
        var latestRequests = 0
        val client = clientWithHandler { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonces[nonceRequests++], Instant.DISTANT_FUTURE),
                )

                "/api/v1/snapshots/latest" -> {
                    assertEquals(
                        nonces[latestRequests],
                        proofNonce(request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty()),
                    )
                    latestRequests += 1
                    if (latestRequests == 1) {
                        respondJson(
                            ApiResponse.Failure(
                                ApiError(CocoaAuthProtocol.NONCE_UNAVAILABLE_ERROR_CODE, "Acquire a new nonce."),
                            ),
                            status = HttpStatusCode.Unauthorized,
                        )
                    } else {
                        respondJson(ApiResponse.Success(LatestSnapshotsPayload(nodes = emptyList())))
                    }
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val api = apiClient(client)

        api.fetchLatestSnapshots()

        assertEquals(2, nonceRequests)
        assertEquals(2, latestRequests)
        api.close()
    }

    @Test
    fun `other unauthorized response is not retried`() = runBlocking {
        var nonceRequests = 0
        var latestRequests = 0
        val client = clientWithHandler { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> {
                    nonceRequests += 1
                    respondJson(NonceIssueResponse(nonce(6), Instant.DISTANT_FUTURE))
                }

                "/api/v1/snapshots/latest" -> {
                    latestRequests += 1
                    respondJson(
                        ApiResponse.Failure(ApiError("principal_disabled", "Principal is disabled.")),
                        status = HttpStatusCode.Unauthorized,
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val api = apiClient(client)

        val error = assertFailsWith<AgentApiException> { api.fetchLatestSnapshots() }

        assertEquals("principal_disabled", error.errorCode)
        assertEquals(1, nonceRequests)
        assertEquals(1, latestRequests)
        api.close()
    }

    @Test
    fun `client surfaces a latest-page policy mismatch`() = runBlocking {
        var latestRequests = 0
        val client = clientWithHandler { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonce(7), Instant.DISTANT_FUTURE),
                )

                "/api/v1/snapshots/latest" -> {
                    val policy = HealthPolicyMetadata("default", latestRequests + 1)
                    val page = LatestSnapshotsPayload(
                        nodes = emptyList(),
                        pagination = if (latestRequests++ == 0) {
                            com.milkcocoa.info.sapphire.core.api.PageMetadata(100, "next", hasMore = true)
                        } else {
                            com.milkcocoa.info.sapphire.core.api.PageMetadata(100)
                        },
                        evaluationPolicy = policy,
                    )
                    respondJson(ApiResponse.Success(page))
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val api = apiClient(client)

        val error = assertFailsWith<LatestSnapshotPolicyMismatchException> { api.fetchLatestSnapshots() }

        assertTrue(error.message.orEmpty().contains("Reload"))
        api.close()
    }

    @Test
    fun `plain HTTP without opt-in is rejected before transport creation`() = runBlocking {
        var factoryCalls = 0
        val blockedProfile = profile.copy(allowInsecureTransport = false)
        val api = AgentApiClient(
            profileStore = FixedProfileStore(blockedProfile),
            credentialStore = FixedCredentialStore(credential),
            httpClientFactory = ConnectionHttpClientFactory {
                factoryCalls += 1
                error("Transport must not be created.")
            },
        )

        val error = assertFailsWith<AgentApiException> { api.fetchLatestSnapshots() }

        assertTrue(error.message.orEmpty().contains("explicit insecure transport opt-in"))
        assertEquals(0, factoryCalls)
    }

    private fun apiClient(httpClient: HttpClient): AgentApiClient = AgentApiClient(
        profileStore = FixedProfileStore(profile),
        credentialStore = FixedCredentialStore(credential),
        httpClientFactory = ConnectionHttpClientFactory { httpClient },
    )

    private fun verifyReadProof(
        authorizationHeaders: List<String>,
        expectedRequest: CanonicalRequest,
    ) {
        SignedRequestJws.verify(
            compactJws = CocoaAuthorization.parse(authorizationHeaders),
            publicKey = keyPair.public,
            expectedRequest = expectedRequest,
        )
    }

    private fun proofNonce(authorizationHeaders: List<String>): String =
        SignedRequestJws.verify(
            CocoaAuthorization.parse(authorizationHeaders),
            keyPair.public,
        ).payload.nonce

    private fun nonce(seed: Int): String = Base64Url.encode(ByteArray(32) { (seed + it).toByte() })
}

private class FixedProfileStore(
    private val profile: ConnectionProfile,
) : ConnectionProfileStore {
    override fun load(): ConnectionProfile = profile

    override fun save(profile: ConnectionProfile) = error("Not used by test.")
}

private class FixedCredentialStore(
    private val credential: ClientCredential,
) : ClientCredentialStore {
    override fun load(path: Path): ClientCredential = credential

    override fun save(path: Path, credential: ClientCredential) = error("Not used by test.")
}

private fun clientWithHandler(
    handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
): HttpClient = HttpClient(MockEngine(handler)) {
    install(ContentNegotiation) {
        json(ClientJson)
    }
}

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    value: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
    additionalHeaders: Map<String, String> = emptyMap(),
): io.ktor.client.request.HttpResponseData {
    val encoded = when (value) {
        is NonceIssueResponse -> ClientJson.encodeToString(value)
        is ApiResponse.Failure -> ClientJson.encodeToString(value)
        is ApiResponse.Success<*> -> when (val payload = value.payload) {
            is LatestSnapshotsPayload -> ClientJson.encodeToString(ApiResponse.Success(payload))
            is NodeDeviceHistoryPayload -> ClientJson.encodeToString(ApiResponse.Success(payload))
            else -> error("Unsupported success payload: ${payload::class}")
        }
        else -> error("Unsupported JSON response: ${value::class}")
    }
    val headers = Headers.build {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        additionalHeaders.forEach(::append)
    }
    return respond(encoded, status, headers)
}
