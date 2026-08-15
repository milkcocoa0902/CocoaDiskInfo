package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredential
import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredentialStore
import com.milkcocoa.info.sapphire.agent.server.NodeJoinRequest
import com.milkcocoa.info.sapphire.agent.server.NodeJoinResponse
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class NodeAgentJoinClientTest {
    @Test
    fun `join generates a key proves the token challenge and stores the credential`() = runBlocking {
        val nonce = nonce(1)
        val tokenSecret = ByteArray(32) { (it + 7).toByte() }
        val tokenId = "00000000-0000-0000-0000-000000000010"
        val hubId = "00000000-0000-0000-0000-000000000020"
        val nodeId = "00000000-0000-0000-0000-000000000030"
        val credentialStore = CapturingCredentialStore()
        var nonceRequest: NonceIssueRequest? = null
        var joinRequest: NodeJoinRequest? = null
        val httpClient = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> {
                    nonceRequest = NodeAgentJson.decodeFromString(request.bodyBytes().decodeToString())
                    respondJson(NonceIssueResponse(nonce, Instant.DISTANT_FUTURE))
                }

                CocoaAuthProtocol.NODE_JOIN_PATH -> {
                    val decoded = NodeAgentJson.decodeFromString<NodeJoinRequest>(request.bodyBytes().decodeToString())
                    joinRequest = decoded
                    respondJson(
                        NodeJoinResponse(
                            hubId = hubId,
                            principalId = "00000000-0000-0000-0000-000000000040",
                            nodeId = nodeId,
                            kid = decoded.kid,
                        ),
                    )
                }

                else -> error("Unexpected request: ${request.url}")
            }
        })
        val client = NodeAgentJoinClient(
            credentialStore = credentialStore,
            httpClientFactory = NodeAgentHttpClientFactory { httpClient },
        )
        val credentialPath = Path.of("/credentials/node.json")

        val credential = client.join(
            NodeAgentJoinCommand(
                connection = NodeAgentConnection("http://hub.local:14631", allowInsecureTransport = true),
                hubId = hubId,
                joinTokenId = tokenId,
                joinTokenSecret = Base64Url.encode(tokenSecret),
                nodeName = "Node A",
                expectedCollectionIntervalSeconds = 60,
                credentialPath = credentialPath,
            ),
        )

        assertEquals(tokenId, nonceRequest?.subjectId)
        val posted = assertNotNull(joinRequest)
        assertTrue(
            JoinProof.verify(
                joinKey = JoinProof.deriveJoinKey(tokenSecret),
                canonicalInput = JoinProof.canonicalInput(
                    hubId = hubId,
                    joinTokenId = tokenId,
                    nonce = nonce,
                    kid = posted.kid,
                    nodeName = "Node A",
                ),
                proof = Base64Url.decodeExact(posted.proof, 32, "proof"),
            ),
        )
        assertEquals(nodeId, credential.nodeId)
        assertEquals("http://hub.local:14631", credential.endpoint)
        assertEquals(credentialPath, credentialStore.savedPath)
        assertEquals(credential, credentialStore.savedCredential)
        assertEquals(credential.kid, Ed25519Keys.kid(credential.publicKeyJwk))
        assertContentEquals(
            credential.publicKeyJwk.x.toByteArray(),
            Ed25519Keys.publicJwk(Ed25519Keys.publicKey(credential.publicKeyJwk)).x.toByteArray(),
        )
    }

    @Test
    fun `join rejects plain HTTP without explicit opt-in before creating a client`() = runBlocking {
        var factoryCalls = 0
        val client = NodeAgentJoinClient(
            credentialStore = CapturingCredentialStore(),
            httpClientFactory = NodeAgentHttpClientFactory {
                factoryCalls += 1
                error("Must not create a client.")
            },
        )

        assertFailsWith<IllegalArgumentException> {
            client.join(
                NodeAgentJoinCommand(
                    connection = NodeAgentConnection("http://hub.local:14631"),
                    hubId = "hub-a",
                    joinTokenId = "token-a",
                    joinTokenSecret = Base64Url.encode(ByteArray(32)),
                    nodeName = "Node A",
                    expectedCollectionIntervalSeconds = 60,
                    credentialPath = Path.of("node.json"),
                ),
            )
        }
        assertEquals(0, factoryCalls)
    }

    @Test
    fun `join does not save a credential when Hub identity does not match`() = runBlocking {
        val store = CapturingCredentialStore()
        val httpClient = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonce(2), Instant.DISTANT_FUTURE),
                )
                CocoaAuthProtocol.NODE_JOIN_PATH -> {
                    val posted = NodeAgentJson.decodeFromString<NodeJoinRequest>(request.bodyBytes().decodeToString())
                    respondJson(NodeJoinResponse("other-hub", "principal-a", "node-a", posted.kid))
                }
                else -> error("Unexpected request: ${request.url}")
            }
        })

        assertFailsWith<NodeAgentTransportException> {
            NodeAgentJoinClient(store, NodeAgentHttpClientFactory { httpClient }).join(
                NodeAgentJoinCommand(
                    connection = NodeAgentConnection("https://hub.example"),
                    hubId = "hub-a",
                    joinTokenId = "token-a",
                    joinTokenSecret = Base64Url.encode(ByteArray(32)),
                    nodeName = "Node A",
                    expectedCollectionIntervalSeconds = 60,
                    credentialPath = Path.of("node.json"),
                ),
            )
        }
        assertEquals(null, store.savedCredential)
    }
}

private class CapturingCredentialStore : NodeAgentCredentialStore {
    var savedPath: Path? = null
    var savedCredential: NodeAgentCredential? = null

    override fun load(path: Path): NodeAgentCredential = error("Not used by test.")

    override fun save(path: Path, credential: NodeAgentCredential) {
        savedPath = path
        savedCredential = credential
    }
}

private fun HttpRequestData.bodyBytes(): ByteArray =
    (body as? OutgoingContent.ByteArrayContent)?.bytes()
        ?: error("Expected a raw byte-array request body, got ${body::class}.")

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    value: Any,
    status: HttpStatusCode = HttpStatusCode.OK,
): io.ktor.client.request.HttpResponseData {
    val body = when (value) {
        is NonceIssueResponse -> NodeAgentJson.encodeToString(value)
        is NodeJoinResponse -> NodeAgentJson.encodeToString(value)
        else -> error("Unsupported response: ${value::class}")
    }
    return respond(
        content = body,
        status = status,
        headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
    )
}

private fun nonce(seed: Int): String = Base64Url.encode(ByteArray(32) { (seed + it).toByte() })
