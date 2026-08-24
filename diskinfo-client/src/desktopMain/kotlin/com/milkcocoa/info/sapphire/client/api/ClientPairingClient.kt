package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.client.credential.ClientCredential
import com.milkcocoa.info.sapphire.client.credential.ClientCredentialStore
import com.milkcocoa.info.sapphire.client.credential.CredentialPrincipalType
import com.milkcocoa.info.sapphire.client.credential.OwnerOnlyJsonClientCredentialStore
import com.milkcocoa.info.sapphire.client.profile.ConnectionProfile
import com.milkcocoa.info.sapphire.client.profile.ConnectionProfileStore
import com.milkcocoa.info.sapphire.client.profile.PreferencesConnectionProfileStore
import com.milkcocoa.info.sapphire.client.profile.validate
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.Ed25519PublicJwk
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.KeyPair

internal data class ClientPairingMaterial(
    val endpoint: String,
    val hubId: String,
    val tokenId: String,
    val tokenSecret: String,
    val displayName: String,
    val allowInsecureTransport: Boolean = false,
    val pemCaPath: String? = null,
    val credentialPath: String,
)

internal data class ClientPairingResult(
    val profile: ConnectionProfile,
    val kid: String,
)

internal fun interface ClientKeyPairFactory {
    fun generate(): KeyPair
}

internal class ClientPairingClient(
    private val profileStore: ConnectionProfileStore = PreferencesConnectionProfileStore(),
    private val credentialStore: ClientCredentialStore = OwnerOnlyJsonClientCredentialStore(),
    private val httpClientFactory: ConnectionHttpClientFactory = DefaultConnectionHttpClientFactory,
    private val keyPairFactory: ClientKeyPairFactory = ClientKeyPairFactory(Ed25519Keys::generate),
) {
    suspend fun pair(material: ClientPairingMaterial): ClientPairingResult {
        val profile = material.toProfile()
        validateTransportOptIn(profile)
        require(material.hubId.isNotBlank()) { "Hub id must not be blank." }
        require(material.tokenId.isNotBlank()) { "Pairing token id must not be blank." }
        require(material.displayName.isNotBlank()) { "Client display name must not be blank." }
        val credentialPath = material.credentialPath.toCredentialPath()
        val tokenSecret = Base64Url.decodeExact(
            material.tokenSecret,
            expectedSize = PAIRING_TOKEN_SECRET_SIZE,
            fieldName = "pairing token secret",
        )

        var transport: HttpClient? = null
        try {
            val keyPair = keyPairFactory.generate()
            val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
            val kid = Ed25519Keys.kid(publicJwk)
            val client = httpClientFactory.create(profile).also { transport = it }
            val nonce = issuePairingNonce(client, profile.baseUrl, material.tokenId)
            val joinKey = JoinProof.deriveJoinKey(tokenSecret)
            val proof = try {
                Base64Url.encode(
                    JoinProof.hmac(
                        joinKey,
                        JoinProof.canonicalInput(
                            hubId = material.hubId,
                            joinTokenId = material.tokenId,
                            nonce = nonce,
                            kid = kid,
                            nodeName = material.displayName,
                        ),
                    ),
                )
            } finally {
                joinKey.fill(0)
            }
            val response = postPairing(
                client = client,
                endpoint = profile.baseUrl,
                request = ClientPairRequest(
                    pairingTokenId = material.tokenId,
                    nonce = nonce,
                    publicKey = publicJwk,
                    kid = kid,
                    displayName = material.displayName,
                    proof = proof,
                ),
            )
            if (response.hubId != material.hubId) {
                throw AgentApiException("Client pairing response hubId does not match the requested Hub.")
            }
            if (response.kid != kid) {
                throw AgentApiException("Client pairing response kid does not match the generated key.")
            }
            if (response.principalId.isBlank()) {
                throw AgentApiException("Client pairing response principalId must not be blank.")
            }

            val credential = ClientCredential(
                hubId = response.hubId,
                endpoint = profile.baseUrl,
                kid = kid,
                privateKeyPkcs8 = Base64Url.encode(keyPair.private.encoded),
                publicKeyJwk = publicJwk,
                principalType = CredentialPrincipalType.CLIENT,
            )
            // Profileを公開する前に、署名鍵をowner-only fileへ確実に保存する。
            credentialStore.save(credentialPath, credential)
            profileStore.save(profile)
            return ClientPairingResult(profile = profile, kid = kid)
        } finally {
            tokenSecret.fill(0)
            transport?.close()
        }
    }

    private suspend fun issuePairingNonce(
        client: HttpClient,
        endpoint: String,
        tokenId: String,
    ): String {
        val response = client.post(endpoint.trim().trimEnd('/') + CocoaAuthProtocol.NONCE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(
                ClientJson.encodeToString(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PAIRING_TOKEN,
                        subjectId = tokenId,
                        purpose = AuthPurpose.CLIENT_PAIR,
                    ),
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
        if (response.status != HttpStatusCode.OK) throw response.toPairingException("Pairing nonce request")
        return decodeRawResponse<NonceIssueResponse>(response, "Pairing nonce request").nonce.also {
            runCatching { Base64Url.decodeExact(it, expectedSize = 32, fieldName = "pairing nonce") }
                .getOrElse { error -> throw AgentApiException("Hub returned an invalid pairing nonce.", cause = error) }
        }
    }

    private suspend fun postPairing(
        client: HttpClient,
        endpoint: String,
        request: ClientPairRequest,
    ): ClientPairResponse {
        val response = client.post(endpoint.trim().trimEnd('/') + CocoaAuthProtocol.CLIENT_PAIR_PATH) {
            contentType(ContentType.Application.Json)
            setBody(ClientJson.encodeToString(request).toByteArray(StandardCharsets.UTF_8))
        }
        if (response.status != HttpStatusCode.OK) throw response.toPairingException("Client pairing")
        return decodeRawResponse(response, "Client pairing")
    }

    private suspend inline fun <reified T> decodeRawResponse(
        response: HttpResponse,
        operation: String,
    ): T = runCatching { ClientJson.decodeFromString<T>(response.bodyAsText()) }
        .getOrElse { throw AgentApiException("$operation returned invalid JSON.", cause = it) }

    private suspend fun HttpResponse.toPairingException(operation: String): AgentApiException {
        val responseBody = bodyAsText()
        val apiError = runCatching {
            ClientJson.decodeFromString<PairingFailureResponse>(responseBody).error
        }.getOrNull()
        return AgentApiException(
            message = "$operation failed: ${status.value} ${apiError?.message ?: responseBody.ifBlank { status.description }}",
            statusCode = status.value,
            errorCode = apiError?.code,
        )
    }

    private fun ClientPairingMaterial.toProfile(): ConnectionProfile = ConnectionProfile(
        id = hubId,
        name = displayName,
        baseUrl = endpoint.trim().trimEnd('/'),
        allowInsecureTransport = allowInsecureTransport,
        credentialPath = credentialPath,
        pemCaPath = pemCaPath?.ifBlank { null },
    ).validate()

    private fun String.toCredentialPath(): Path {
        if (isBlank()) throw AgentApiException("Client credential path must not be blank.")
        return try {
            Path.of(this)
        } catch (error: InvalidPathException) {
            throw AgentApiException("Client credential path is invalid: $this", cause = error)
        }
    }

    companion object {
        private const val PAIRING_TOKEN_SECRET_SIZE = 32
    }
}

@Serializable
internal data class ClientPairRequest(
    val pairingTokenId: String,
    val nonce: String,
    val publicKey: Ed25519PublicJwk,
    val kid: String,
    val displayName: String,
    val proof: String,
)

@Serializable
internal data class ClientPairResponse(
    val hubId: String,
    val principalId: String,
    val kid: String,
)

@Serializable
private data class PairingFailureResponse(
    val error: ApiError,
)
