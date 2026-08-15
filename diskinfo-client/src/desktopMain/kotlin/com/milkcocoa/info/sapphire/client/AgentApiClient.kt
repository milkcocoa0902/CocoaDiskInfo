package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequestCodec
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.Ed25519Keys
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.PrivateKey
import kotlin.time.Instant

internal val ClientJson = Json {
    ignoreUnknownKeys = true
}

enum class SnapshotHistoryOrder(
    val wireValue: String,
) {
    ASC("asc"),
    DESC("desc"),
}

fun interface ConnectionHttpClientFactory {
    fun create(profile: ConnectionProfile): HttpClient
}

class AgentApiClient(
    private val profileStore: ConnectionProfileStore = PreferencesConnectionProfileStore(),
    private val credentialStore: ClientCredentialStore = OwnerOnlyJsonClientCredentialStore(),
    private val httpClientFactory: ConnectionHttpClientFactory = DefaultConnectionHttpClientFactory,
) : AutoCloseable {
    private val signedRequestMutex = Mutex()
    private var activeTransport: ActiveTransport? = null
    private var nextNonce: CachedNonce? = null

    suspend fun fetchLatestSnapshots(
        limit: Int = DEFAULT_LATEST_LIMIT,
    ): LatestSnapshotsPayload {
        require(limit in 1..MAX_LATEST_LIMIT) {
            "Latest snapshot limit must be between 1 and $MAX_LATEST_LIMIT."
        }
        val context = requestContext()
        val pages = mutableListOf<LatestSnapshotsPayload>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null

        do {
            val effectiveValues = mapOf(
                "cursor" to cursor,
                "limit" to limit.toString(),
            )
            val canonicalRequest = CanonicalRequest(
                method = "GET",
                path = CanonicalRequestCodec.path("api", "v1", "snapshots", "latest"),
                query = CanonicalRequestCodec.query(effectiveValues),
                purpose = AuthPurpose.CLIENT_READ,
            )
            val url = URLBuilder(context.profile.baseUrl.trim().trimEnd('/'))
                .appendPathSegments("api", "v1", "snapshots", "latest")
                .apply {
                    cursor?.let { parameters.append("cursor", it) }
                    parameters.append("limit", limit.toString())
                }
                .buildString()
            val response = signedGet(context, url, canonicalRequest)
            val page = response.body<LatestSnapshotsResponse>().payload
            pages += page

            val pagination = page.pagination
            val nextCursor = pagination?.nextCursor
            cursor = when {
                pagination?.hasMore != true -> null
                nextCursor.isNullOrBlank() -> throw AgentApiException(
                    "Agent returned hasMore=true without a next cursor.",
                )
                !seenCursors.add(nextCursor) -> throw AgentApiException(
                    "Agent returned a repeated pagination cursor.",
                )
                else -> nextCursor
            }
        } while (cursor != null)

        return mergeLatestSnapshotPages(pages)
    }

    suspend fun fetchDeviceHistory(
        nodeId: String,
        deviceKey: String,
        limit: Int = DEFAULT_HISTORY_LIMIT,
        from: Instant? = null,
        to: Instant? = null,
        order: SnapshotHistoryOrder = SnapshotHistoryOrder.DESC,
    ): NodeDeviceHistoryPayload {
        require(nodeId.isNotBlank()) { "Node id must not be blank." }
        require(deviceKey.isNotBlank()) { "Device key must not be blank." }
        require(limit in 1..MAX_HISTORY_LIMIT) {
            "History limit must be between 1 and $MAX_HISTORY_LIMIT."
        }
        require(from == null || to == null || from <= to) {
            "History from timestamp must be earlier than or equal to to timestamp."
        }

        val context = requestContext()
        val effectiveValues = mapOf(
            "from" to from?.toString(),
            "limit" to limit.toString(),
            "order" to order.wireValue,
            "to" to to?.toString(),
        )
        val canonicalRequest = CanonicalRequest(
            method = "GET",
            path = CanonicalRequestCodec.path(
                "api",
                "v1",
                "nodes",
                nodeId,
                "devices",
                deviceKey,
                "snapshots",
            ),
            query = CanonicalRequestCodec.query(effectiveValues),
            purpose = AuthPurpose.CLIENT_READ,
        )
        val url = URLBuilder(context.profile.baseUrl.trim().trimEnd('/'))
            .appendPathSegments("api", "v1", "nodes", nodeId, "devices", deviceKey, "snapshots")
            .apply {
                from?.let { parameters.append("from", it.toString()) }
                parameters.append("limit", limit.toString())
                parameters.append("order", order.wireValue)
                to?.let { parameters.append("to", it.toString()) }
            }
            .buildString()
        val response = signedGet(context, url, canonicalRequest)
        return response.body<DeviceHistoryResponse>().payload
    }

    override fun close() {
        synchronized(this) {
            activeTransport?.client?.close()
            activeTransport = null
            nextNonce = null
        }
    }

    private fun requestContext(): SignedRequestContext {
        val profile = runCatching { profileStore.load().validate() }
            .getOrElse { throw AgentApiException(it.message ?: "Connection profile is invalid.", cause = it) }
        validateTransportOptIn(profile)
        val credentialPath = profile.credentialPath
            ?: throw AgentApiException("Client credential registration is required for this connection profile.")
        val path = try {
            Path.of(credentialPath)
        } catch (error: InvalidPathException) {
            throw AgentApiException("Client credential path is invalid: $credentialPath", cause = error)
        }
        val credential = runCatching { credentialStore.load(path) }
            .getOrElse { throw AgentApiException(it.message ?: "Failed to load client credential.", cause = it) }
        val privateKey = runCatching {
            Ed25519Keys.privateKeyFromPkcs8(
                Base64Url.decode(credential.privateKeyPkcs8, "Client credential privateKeyPkcs8"),
            )
        }.getOrElse { throw AgentApiException("Client signing key is invalid.", cause = it) }

        return SignedRequestContext(
            profile = profile,
            credential = credential,
            privateKey = privateKey,
            client = clientFor(profile),
        )
    }

    private suspend fun signedGet(
        context: SignedRequestContext,
        url: String,
        canonicalRequest: CanonicalRequest,
    ): HttpResponse = signedRequestMutex.withLock {
        val cacheKey = NonceCacheKey(context.profile.baseUrl, context.credential.kid)
        var nonce = nextNonce
            ?.takeIf { it.key == cacheKey }
            ?.value
            ?: issueNonce(context)
        nextNonce = null

        repeat(2) { attempt ->
            val proof = SignedRequestJws.sign(
                canonicalRequest.signedPayload(nonce),
                context.privateKey,
                context.credential.kid,
            )
            val response = context.client.get(url) {
                header(
                    CocoaAuthProtocol.AUTHORIZATION_HEADER,
                    CocoaAuthorization.format(proof),
                )
            }
            if (response.status == HttpStatusCode.OK) {
                nextNonce = response.nextNonceOrNull()?.let { CachedNonce(cacheKey, it) }
                return@withLock response
            }

            val failure = response.failure()
            if (attempt == 0 &&
                response.status == HttpStatusCode.Unauthorized &&
                failure.errorCode == CocoaAuthProtocol.NONCE_UNAVAILABLE_ERROR_CODE
            ) {
                nonce = issueNonce(context)
            } else {
                throw failure.toException()
            }
        }
        error("Signed request retry loop completed unexpectedly.")
    }

    private suspend fun issueNonce(context: SignedRequestContext): String {
        val url = URLBuilder(context.profile.baseUrl.trim().trimEnd('/'))
            .appendPathSegments("api", "v1", "auth", "nonces")
            .buildString()
        val response = context.client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                NonceIssueRequest(
                    subjectType = NonceSubjectType.PRINCIPAL,
                    subjectId = context.credential.kid,
                    purpose = AuthPurpose.CLIENT_READ,
                ),
            )
        }
        if (response.status != HttpStatusCode.OK) {
            throw response.failure().toException()
        }
        return response.body<NonceIssueResponse>().nonce.also {
            runCatching { Base64Url.decodeExact(it, expectedSize = 32, fieldName = "nonce") }
                .getOrElse { error -> throw AgentApiException("Agent returned an invalid nonce.", cause = error) }
        }
    }

    @Synchronized
    private fun clientFor(profile: ConnectionProfile): HttpClient {
        val current = activeTransport
        if (current?.profile == profile) return current.client

        current?.client?.close()
        return httpClientFactory.create(profile).also {
            activeTransport = ActiveTransport(profile, it)
            nextNonce = null
        }
    }

    private fun HttpResponse.nextNonceOrNull(): String? {
        val values = headers.getAll(CocoaAuthProtocol.NEXT_NONCE_HEADER) ?: return null
        if (values.size != 1) {
            throw AgentApiException("Agent returned multiple next nonce headers.")
        }
        return values.single().also {
            runCatching { Base64Url.decodeExact(it, expectedSize = 32, fieldName = "next nonce") }
                .getOrElse { error -> throw AgentApiException("Agent returned an invalid next nonce.", cause = error) }
        }
    }

    private suspend fun HttpResponse.failure(): ParsedFailure {
        val body = bodyAsText()
        val apiError = runCatching {
            ClientJson.decodeFromString<FailureResponse>(body).error
        }.getOrNull()
        return ParsedFailure(
            status = status,
            errorCode = apiError?.code,
            message = apiError?.message ?: body.ifBlank { status.description },
        )
    }

    private data class ActiveTransport(
        val profile: ConnectionProfile,
        val client: HttpClient,
    )

    private data class SignedRequestContext(
        val profile: ConnectionProfile,
        val credential: ClientCredential,
        val privateKey: PrivateKey,
        val client: HttpClient,
    )

    private data class NonceCacheKey(
        val baseUrl: String,
        val kid: String,
    )

    private data class CachedNonce(
        val key: NonceCacheKey,
        val value: String,
    )

    private data class ParsedFailure(
        val status: HttpStatusCode,
        val errorCode: String?,
        val message: String,
    ) {
        fun toException(): AgentApiException = AgentApiException(
            message = "Agent request failed: ${status.value} $message",
            statusCode = status.value,
            errorCode = errorCode,
        )
    }

    companion object {
        private const val DEFAULT_LATEST_LIMIT = 100
        private const val MAX_LATEST_LIMIT = 500
        private const val DEFAULT_HISTORY_LIMIT = 100
        private const val MAX_HISTORY_LIMIT = 1000
    }
}

class AgentApiException(
    message: String,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
private data class LatestSnapshotsResponse(
    val payload: LatestSnapshotsPayload,
)

@Serializable
private data class DeviceHistoryResponse(
    val payload: NodeDeviceHistoryPayload,
)

@Serializable
private data class FailureResponse(
    val error: ApiError,
)
