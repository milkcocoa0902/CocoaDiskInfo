package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestRequest
import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestResponse
import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestStatus
import com.milkcocoa.info.sapphire.agent.server.NodeHeartbeatRequest
import com.milkcocoa.info.sapphire.agent.server.NodeHeartbeatResponse
import com.milkcocoa.info.sapphire.agent.server.NodeReportedError
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthErrorCodes
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import com.milkcocoa.info.sapphire.core.auth.SnapshotBodyDigest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

class SignedNodeAgentTransportTest {
    private val keyPair = Ed25519Keys.generate()
    private val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
    private val credential = com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredential(
        privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
        publicKeyJwk = publicJwk,
        kid = Ed25519Keys.kid(publicJwk),
        hubId = "hub-a",
        nodeId = "node-a",
        nodeName = "Node A",
        endpoint = "https://hub.example",
    )
    private val snapshot = testDiskSnapshot("signed-transport-device", 1_000)

    @Test
    fun `ingest signs the exact raw JSON bytes and reuses a valid next nonce`() = runBlocking {
        val issuedNonce = nonceValue(1)
        val nextNonce = nonceValue(2)
        var nonceRequests = 0
        var ingestRequests = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> {
                    nonceRequests += 1
                    respondJson(NonceIssueResponse(issuedNonce, Instant.DISTANT_FUTURE))
                }

                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> {
                    val expectedNonce = if (ingestRequests == 0) issuedNonce else nextNonce
                    val bodyBytes = request.rawBodyBytes()
                    verifySignedBody(request, bodyBytes, expectedNonce)
                    val ingest = NodeAgentJson.decodeFromString<SnapshotIngestRequest>(bodyBytes.decodeToString())
                    ingestRequests += 1
                    respondJson(
                        SnapshotIngestResponse(
                            ingestId = ingest.ingestId,
                            status = SnapshotIngestStatus.STORED,
                            snapshotId = "snapshot-$ingestRequests",
                            receivedAt = Instant.fromEpochMilliseconds(2_000),
                        ),
                        additionalHeaders = if (ingestRequests == 1) {
                            mapOf(CocoaAuthProtocol.NEXT_NONCE_HEADER to nextNonce)
                        } else {
                            emptyMap()
                        },
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        })
        val transport = transport(client)

        transport.ingest(SnapshotIngestRequest("ingest-a", snapshot))
        transport.ingest(SnapshotIngestRequest("ingest-b", snapshot))

        assertEquals(1, nonceRequests)
        assertEquals(2, ingestRequests)
    }

    @Test
    fun `transport failure retries with identical body and ingest id but a fresh nonce`() = runBlocking {
        val nonces = listOf(nonceValue(3), nonceValue(4))
        val sentBodies = mutableListOf<ByteArray>()
        val signedNonces = mutableListOf<String>()
        var nonceRequests = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonces[nonceRequests++], Instant.DISTANT_FUTURE),
                )

                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> {
                    val bytes = request.rawBodyBytes()
                    sentBodies += bytes
                    signedNonces += SignedRequestJws.verify(
                        CocoaAuthorization.parse(
                            request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
                        ),
                        keyPair.public,
                    ).payload.nonce
                    if (sentBodies.size == 1) throw IOException("response lost after store")
                    val ingest = NodeAgentJson.decodeFromString<SnapshotIngestRequest>(bytes.decodeToString())
                    respondJson(
                        SnapshotIngestResponse(
                            ingest.ingestId,
                            SnapshotIngestStatus.DUPLICATE,
                            "snapshot-a",
                            Instant.fromEpochMilliseconds(2_000),
                        ),
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        })

        val response = transport(client).ingest(SnapshotIngestRequest("stable-ingest-id", snapshot))

        assertEquals(SnapshotIngestStatus.DUPLICATE, response.status)
        assertEquals(2, sentBodies.size)
        assertContentEquals(sentBodies[0], sentBodies[1])
        assertEquals(nonces, signedNonces)
        assertEquals("stable-ingest-id", NodeAgentJson.decodeFromString<SnapshotIngestRequest>(sentBodies[0].decodeToString()).ingestId)
    }

    @Test
    fun `only nonce unavailable 401 retries among client errors`() = runBlocking {
        var nonceRequests = 0
        var ingestRequests = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonceValue(++nonceRequests), Instant.DISTANT_FUTURE),
                )

                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> {
                    ingestRequests += 1
                    if (ingestRequests == 1) {
                        respondJson(
                            ApiResponse.Failure(ApiError(CocoaAuthErrorCodes.NONCE_UNAVAILABLE, "nonce unavailable")),
                            status = HttpStatusCode.Unauthorized,
                        )
                    } else {
                        val ingest = NodeAgentJson.decodeFromString<SnapshotIngestRequest>(
                            request.rawBodyBytes().decodeToString(),
                        )
                        respondJson(
                            SnapshotIngestResponse(
                                ingest.ingestId,
                                SnapshotIngestStatus.STORED,
                                "snapshot-a",
                                Instant.fromEpochMilliseconds(2_000),
                            ),
                        )
                    }
                }

                else -> error("Unexpected request: ${request.url}")
            }
        })

        transport(client).ingest(SnapshotIngestRequest("ingest-a", snapshot))

        assertEquals(2, nonceRequests)
        assertEquals(2, ingestRequests)

        var permanentRequests = 0
        val permanentClient = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonceValue(10), Instant.DISTANT_FUTURE),
                )
                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> {
                    permanentRequests += 1
                    respondJson(
                        ApiResponse.Failure(ApiError("authentication_invalid", "bad signature")),
                        status = HttpStatusCode.Unauthorized,
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val failure = assertFailsWith<NodeAgentTransportException> {
            transport(permanentClient).ingest(SnapshotIngestRequest("ingest-b", snapshot))
        }
        assertEquals("authentication_invalid", failure.errorCode)
        assertEquals(1, permanentRequests)
    }

    @Test
    fun `ordinary 4xx is not retried and cancellation propagates`() = runBlocking {
        var conflictRequests = 0
        val conflictClient = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonceValue(20), Instant.DISTANT_FUTURE),
                )
                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> {
                    conflictRequests += 1
                    respondJson(
                        ApiResponse.Failure(ApiError("ingest_id_conflict", "conflicting payload")),
                        status = HttpStatusCode.Conflict,
                    )
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })

        assertFailsWith<NodeAgentTransportException> {
            transport(conflictClient).ingest(SnapshotIngestRequest("ingest-a", snapshot))
        }
        assertEquals(1, conflictRequests)

        val cancellation = CancellationException("stopping")
        val cancellationClient = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonceValue(21), Instant.DISTANT_FUTURE),
                )
                CocoaAuthProtocol.SNAPSHOT_INGEST_PATH -> throw cancellation
                else -> error("Unexpected request: ${request.url}")
            }
        })
        val thrown = assertFailsWith<CancellationException> {
            transport(cancellationClient).ingest(SnapshotIngestRequest("ingest-b", snapshot))
        }
        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun `heartbeat is independently nonce bound and signs its raw body`() = runBlocking {
        val heartbeatNonce = nonceValue(30)
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(heartbeatNonce, Instant.DISTANT_FUTURE),
                )
                CocoaAuthProtocol.HEARTBEAT_PATH -> {
                    val bodyBytes = request.rawBodyBytes()
                    val expectedRequest = CanonicalRequest(
                        method = "POST",
                        path = CocoaAuthProtocol.HEARTBEAT_PATH,
                        query = "",
                        purpose = AuthPurpose.HEARTBEAT,
                        bodySha256 = SnapshotBodyDigest.bodySha256(bodyBytes),
                    )
                    val verified = SignedRequestJws.verify(
                        CocoaAuthorization.parse(
                            request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
                        ),
                        keyPair.public,
                        expectedRequest,
                    )
                    assertEquals(heartbeatNonce, verified.payload.nonce)
                    assertEquals(
                        SnapshotBodyDigest.contentDigest(bodyBytes),
                        request.headers[CocoaAuthProtocol.CONTENT_DIGEST_HEADER],
                    )
                    assertEquals(
                        NodeHeartbeatRequest(60, NodeReportedError("collection_failed", "smartctl failed")),
                        NodeAgentJson.decodeFromString(bodyBytes.decodeToString()),
                    )
                    respondJson(NodeHeartbeatResponse(Instant.fromEpochMilliseconds(3_000)))
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })

        val response = transport(client).heartbeat(
            NodeHeartbeatRequest(60, NodeReportedError("collection_failed", "smartctl failed")),
        )

        assertEquals(Instant.fromEpochMilliseconds(3_000), response.receivedAt)
    }

    private fun transport(client: HttpClient): SignedNodeAgentTransport = SignedNodeAgentTransport(
        credential = credential,
        client = client,
        retryPolicy = NodeDeliveryRetryPolicy(
            maximumAttempts = 3,
            initialBackoff = Duration.ZERO,
            maximumBackoff = Duration.ZERO,
        ),
        waitBeforeRetry = {},
    )

    private fun verifySignedBody(
        request: HttpRequestData,
        bodyBytes: ByteArray,
        expectedNonce: String,
    ) {
        val digest = request.headers[CocoaAuthProtocol.CONTENT_DIGEST_HEADER]
        assertEquals(SnapshotBodyDigest.contentDigest(bodyBytes), digest)
        val expectedRequest = CanonicalRequest(
            method = "POST",
            path = CocoaAuthProtocol.SNAPSHOT_INGEST_PATH,
            query = "",
            purpose = AuthPurpose.SNAPSHOT_INGEST,
            bodySha256 = SnapshotBodyDigest.bodySha256(bodyBytes),
        )
        val verified = SignedRequestJws.verify(
            CocoaAuthorization.parse(
                request.headers.getAll(CocoaAuthProtocol.AUTHORIZATION_HEADER).orEmpty(),
            ),
            keyPair.public,
            expectedRequest,
        )
        assertEquals(expectedNonce, verified.payload.nonce)
    }
}

private fun HttpRequestData.rawBodyBytes(): ByteArray =
    (body as? OutgoingContent.ByteArrayContent)?.bytes()
        ?: error("Expected raw byte-array request body, got ${body::class}.")

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    value: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
    additionalHeaders: Map<String, String> = emptyMap(),
): io.ktor.client.request.HttpResponseData {
    val body = when (value) {
        is NonceIssueResponse -> NodeAgentJson.encodeToString(value)
        is SnapshotIngestResponse -> NodeAgentJson.encodeToString(value)
        is NodeHeartbeatResponse -> NodeAgentJson.encodeToString(value)
        is ApiResponse.Failure -> NodeAgentJson.encodeToString(value)
        else -> error("Unsupported response: ${value::class}")
    }
    return respond(
        content = body,
        status = status,
        headers = Headers.build {
            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            additionalHeaders.forEach(::append)
        },
    )
}

private fun nonceValue(seed: Int): String = Base64Url.encode(ByteArray(32) { (seed + it).toByte() })
