package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredential
import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredentialStore
import com.milkcocoa.info.sapphire.agent.credential.OwnerOnlyJsonNodeAgentCredentialStore
import com.milkcocoa.info.sapphire.agent.server.NodeJoinRequest
import com.milkcocoa.info.sapphire.agent.server.NodeJoinResponse
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.Path

data class NodeAgentJoinCommand(
    val connection: NodeAgentConnection,
    val hubId: String,
    val joinTokenId: String,
    val joinTokenSecret: String,
    val nodeName: String,
    val expectedCollectionIntervalSeconds: Long,
    val credentialPath: Path,
)

class NodeAgentJoinClient(
    private val credentialStore: NodeAgentCredentialStore = OwnerOnlyJsonNodeAgentCredentialStore(),
    private val httpClientFactory: NodeAgentHttpClientFactory = DefaultNodeAgentHttpClientFactory,
) {
    suspend fun join(command: NodeAgentJoinCommand): NodeAgentCredential {
        val connection = command.connection.validate()
        require(command.hubId.isNotBlank()) { "Hub id must not be blank." }
        require(command.joinTokenId.isNotBlank()) { "Join token id must not be blank." }
        require(command.nodeName.isNotBlank()) { "Node name must not be blank." }
        require(command.expectedCollectionIntervalSeconds > 0) {
            "Expected collection interval must be greater than zero."
        }
        val tokenSecret = Base64Url.decodeExact(
            command.joinTokenSecret,
            expectedSize = JOIN_TOKEN_SECRET_SIZE,
            fieldName = "join token secret",
        )

        val keyPair = Ed25519Keys.generate()
        val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
        val kid = Ed25519Keys.kid(publicJwk)
        val client = httpClientFactory.create(connection)
        try {
            val nonce = issueJoinNonce(client, connection.endpoint, command.joinTokenId)
            val joinKey = JoinProof.deriveJoinKey(tokenSecret)
            val proof = try {
                Base64Url.encode(
                    JoinProof.hmac(
                        joinKey,
                        JoinProof.canonicalInput(
                            hubId = command.hubId,
                            joinTokenId = command.joinTokenId,
                            nonce = nonce,
                            kid = kid,
                            nodeName = command.nodeName,
                        ),
                    ),
                )
            } finally {
                joinKey.fill(0)
            }
            val response = postJoin(
                client = client,
                endpoint = connection.endpoint,
                request = NodeJoinRequest(
                    joinTokenId = command.joinTokenId,
                    nonce = nonce,
                    publicKey = publicJwk,
                    kid = kid,
                    nodeName = command.nodeName,
                    expectedCollectionIntervalSeconds = command.expectedCollectionIntervalSeconds,
                    proof = proof,
                ),
            )
            if (response.hubId != command.hubId) {
                throw NodeAgentTransportException("Node join response hubId does not match the requested Hub.")
            }
            if (response.kid != kid) {
                throw NodeAgentTransportException("Node join response kid does not match the generated key.")
            }
            if (response.nodeId.isBlank()) {
                throw NodeAgentTransportException("Node join response nodeId must not be blank.")
            }
            if (response.principalId.isBlank()) {
                throw NodeAgentTransportException("Node join response principalId must not be blank.")
            }

            return NodeAgentCredential(
                privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
                publicKeyJwk = publicJwk,
                kid = kid,
                hubId = response.hubId,
                nodeId = response.nodeId,
                nodeName = command.nodeName,
                endpoint = connection.endpoint,
            ).also { credentialStore.save(command.credentialPath, it) }
        } finally {
            tokenSecret.fill(0)
            client.close()
        }
    }

    private suspend fun issueJoinNonce(
        client: HttpClient,
        endpoint: String,
        tokenId: String,
    ): String {
        val response = client.post(endpoint + CocoaAuthProtocol.NONCE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(
                NodeAgentJson.encodeToString(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.JOIN_TOKEN,
                        subjectId = tokenId,
                        purpose = AuthPurpose.JOIN,
                    ),
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
        if (response.status != HttpStatusCode.OK) throw response.toFailureException("Join nonce request")
        val nonceResponse = decodeRawResponse<NonceIssueResponse>(response.bodyAsText(), "Join nonce request")
        return nonceResponse.nonce.also {
            runCatching { Base64Url.decodeExact(it, expectedSize = 32, fieldName = "join nonce") }
                .getOrElse { error -> throw NodeAgentTransportException("Hub returned an invalid join nonce.", cause = error) }
        }
    }

    private suspend fun postJoin(
        client: HttpClient,
        endpoint: String,
        request: NodeJoinRequest,
    ): NodeJoinResponse {
        val response = client.post(endpoint + CocoaAuthProtocol.NODE_JOIN_PATH) {
            contentType(ContentType.Application.Json)
            setBody(NodeAgentJson.encodeToString(request).toByteArray(StandardCharsets.UTF_8))
        }
        if (response.status != HttpStatusCode.OK) throw response.toFailureException("Node join")
        return decodeRawResponse(response.bodyAsText(), "Node join")
    }

    companion object {
        private const val JOIN_TOKEN_SECRET_SIZE = 32
    }
}
