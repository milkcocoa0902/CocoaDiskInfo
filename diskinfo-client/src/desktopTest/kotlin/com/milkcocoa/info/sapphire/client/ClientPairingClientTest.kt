package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.JoinProof
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ClientPairingClientTest {
    private val keyPair = Ed25519Keys.generate()
    private val publicJwk = Ed25519Keys.publicJwk(keyPair.public)
    private val kid = Ed25519Keys.kid(publicJwk)
    private val tokenSecretBytes = ByteArray(32) { (it + 1).toByte() }
    private val tokenSecret = Base64Url.encode(tokenSecretBytes)
    private val nonce = Base64Url.encode(ByteArray(32) { (it + 41).toByte() })
    private val material = ClientPairingMaterial(
        endpoint = "http://hub.local:14631/",
        hubId = "hub-a",
        tokenId = "pair-token-a",
        tokenSecret = tokenSecret,
        displayName = "Desktop A",
        allowInsecureTransport = true,
        pemCaPath = "/certificates/private-ca.pem",
        credentialPath = "/credentials/client.json",
    )

    @Test
    fun `pairing requests raw nonce and persists credential before profile`() = runBlocking {
        val events = mutableListOf<String>()
        val profileStore = RecordingProfileStore(events)
        val credentialStore = RecordingCredentialStore(events)
        var requestCount = 0
        val httpClient = pairingHttpClient { request ->
            requestCount += 1
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> {
                    val nonceRequest = ClientJson.decodeFromString<NonceIssueRequest>(
                        request.body.toByteArray().toString(StandardCharsets.UTF_8),
                    )
                    assertEquals(NonceSubjectType.PAIRING_TOKEN, nonceRequest.subjectType)
                    assertEquals(material.tokenId, nonceRequest.subjectId)
                    assertEquals(AuthPurpose.CLIENT_PAIR, nonceRequest.purpose)
                    respondJson(NonceIssueResponse(nonce, Instant.DISTANT_FUTURE))
                }

                CocoaAuthProtocol.CLIENT_PAIR_PATH -> {
                    val pairRequest = ClientJson.decodeFromString<ClientPairRequest>(
                        request.body.toByteArray().toString(StandardCharsets.UTF_8),
                    )
                    assertEquals(material.tokenId, pairRequest.pairingTokenId)
                    assertEquals(nonce, pairRequest.nonce)
                    assertEquals(publicJwk, pairRequest.publicKey)
                    assertEquals(kid, pairRequest.kid)
                    assertEquals(material.displayName, pairRequest.displayName)
                    assertEquals(
                        Base64Url.encode(
                            JoinProof.hmac(
                                JoinProof.deriveJoinKey(tokenSecretBytes),
                                JoinProof.canonicalInput(
                                    hubId = material.hubId,
                                    joinTokenId = material.tokenId,
                                    nonce = nonce,
                                    kid = kid,
                                    nodeName = material.displayName,
                                ),
                            ),
                        ),
                        pairRequest.proof,
                    )
                    respondJson(ClientPairResponse(material.hubId, "principal-a", kid))
                }

                else -> error("Unexpected request: ${request.url}")
            }
        }
        val pairingClient = pairingClient(profileStore, credentialStore, httpClient)

        val result = pairingClient.pair(material)

        assertEquals(2, requestCount)
        assertEquals(listOf("credential", "profile"), events)
        assertEquals(kid, result.kid)
        assertEquals(material.hubId, result.profile.id)
        assertEquals("http://hub.local:14631", result.profile.baseUrl)
        assertEquals(material.displayName, result.profile.name)
        assertEquals(material.pemCaPath, result.profile.pemCaPath)
        val savedCredential = assertNotNull(credentialStore.savedCredential)
        assertEquals(Path.of(material.credentialPath), credentialStore.savedPath)
        assertEquals(material.hubId, savedCredential.hubId)
        assertEquals(kid, savedCredential.kid)
        assertEquals(publicJwk, savedCredential.publicKeyJwk)
        assertEquals(CredentialPrincipalType.CLIENT, savedCredential.principalType)
        assertEquals(
            Base64Url.encode(keyPair.private.encoded),
            savedCredential.privateKeyPkcs8,
        )
        val savedProfileJson = ClientJson.encodeToString(assertNotNull(profileStore.savedProfile))
        assertFalse(savedProfileJson.contains(tokenSecret))
        assertFalse(savedProfileJson.contains(savedCredential.privateKeyPkcs8))
    }

    @Test
    fun `hub mismatch does not persist credential or profile`() = runBlocking {
        val profileStore = RecordingProfileStore()
        val credentialStore = RecordingCredentialStore()
        val httpClient = pairingHttpClient { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonce, Instant.DISTANT_FUTURE),
                )

                CocoaAuthProtocol.CLIENT_PAIR_PATH -> respondJson(
                    ClientPairResponse("unexpected-hub", "principal-a", kid),
                )

                else -> error("Unexpected request: ${request.url}")
            }
        }

        val error = assertFailsWith<AgentApiException> {
            pairingClient(profileStore, credentialStore, httpClient).pair(material)
        }

        assertTrue(error.message.orEmpty().contains("hubId"))
        assertEquals(null, profileStore.savedProfile)
        assertEquals(null, credentialStore.savedCredential)
    }

    @Test
    fun `kid mismatch does not persist credential or profile`() = runBlocking {
        val profileStore = RecordingProfileStore()
        val credentialStore = RecordingCredentialStore()
        val httpClient = pairingHttpClient { request ->
            when (request.url.encodedPath) {
                CocoaAuthProtocol.NONCE_PATH -> respondJson(
                    NonceIssueResponse(nonce, Instant.DISTANT_FUTURE),
                )

                CocoaAuthProtocol.CLIENT_PAIR_PATH -> respondJson(
                    ClientPairResponse(material.hubId, "principal-a", Base64Url.encode(ByteArray(32))),
                )

                else -> error("Unexpected request: ${request.url}")
            }
        }

        val error = assertFailsWith<AgentApiException> {
            pairingClient(profileStore, credentialStore, httpClient).pair(material)
        }

        assertTrue(error.message.orEmpty().contains("kid"))
        assertEquals(null, profileStore.savedProfile)
        assertEquals(null, credentialStore.savedCredential)
    }

    @Test
    fun `plain HTTP without opt-in is rejected before key or transport creation`() = runBlocking {
        var generatedKeys = 0
        var factoryCalls = 0
        val pairingClient = ClientPairingClient(
            profileStore = RecordingProfileStore(),
            credentialStore = RecordingCredentialStore(),
            httpClientFactory = ConnectionHttpClientFactory {
                factoryCalls += 1
                error("Transport must not be created.")
            },
            keyPairFactory = ClientKeyPairFactory {
                generatedKeys += 1
                keyPair
            },
        )

        assertFailsWith<AgentApiException> {
            pairingClient.pair(material.copy(allowInsecureTransport = false))
        }

        assertEquals(0, generatedKeys)
        assertEquals(0, factoryCalls)
    }

    private fun pairingClient(
        profileStore: RecordingProfileStore,
        credentialStore: RecordingCredentialStore,
        httpClient: HttpClient,
    ): ClientPairingClient = ClientPairingClient(
        profileStore = profileStore,
        credentialStore = credentialStore,
        httpClientFactory = ConnectionHttpClientFactory { httpClient },
        keyPairFactory = ClientKeyPairFactory { keyPair },
    )
}

private class RecordingProfileStore(
    private val events: MutableList<String>? = null,
) : ConnectionProfileStore {
    var savedProfile: ConnectionProfile? = null

    override fun load(): ConnectionProfile = error("Not used by test.")

    override fun save(profile: ConnectionProfile) {
        events?.add("profile")
        savedProfile = profile
    }
}

private class RecordingCredentialStore(
    private val events: MutableList<String>? = null,
) : ClientCredentialStore {
    var savedPath: Path? = null
    var savedCredential: ClientCredential? = null

    override fun load(path: Path): ClientCredential = error("Not used by test.")

    override fun save(path: Path, credential: ClientCredential) {
        events?.add("credential")
        savedPath = path
        savedCredential = credential
    }
}

private fun pairingHttpClient(
    handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
): HttpClient = HttpClient(MockEngine(handler))

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    response: NonceIssueResponse,
): io.ktor.client.request.HttpResponseData = respond(
    ClientJson.encodeToString(response),
    HttpStatusCode.OK,
    jsonHeaders(),
)

private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(
    response: ClientPairResponse,
): io.ktor.client.request.HttpResponseData = respond(
    ClientJson.encodeToString(response),
    HttpStatusCode.OK,
    jsonHeaders(),
)

private fun jsonHeaders(): Headers = Headers.build {
    append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
}
