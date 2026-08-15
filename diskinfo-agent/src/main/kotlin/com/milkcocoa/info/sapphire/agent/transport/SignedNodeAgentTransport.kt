package com.milkcocoa.info.sapphire.agent.transport

import com.milkcocoa.info.sapphire.agent.credential.NodeAgentCredential
import com.milkcocoa.info.sapphire.agent.credential.privateKey
import com.milkcocoa.info.sapphire.agent.credential.validateNodeAgentCredential
import com.milkcocoa.info.sapphire.agent.server.NodeHeartbeatRequest
import com.milkcocoa.info.sapphire.agent.server.NodeHeartbeatResponse
import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestRequest
import com.milkcocoa.info.sapphire.agent.server.SnapshotIngestResponse
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.Base64Url
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthErrorCodes
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthorization
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.auth.NonceSubjectType
import com.milkcocoa.info.sapphire.core.auth.SignedRequestJws
import com.milkcocoa.info.sapphire.core.auth.SnapshotBodyDigest
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class NodeDeliveryRetryPolicy(
    val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
    val initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    val maximumBackoff: Duration = DEFAULT_MAXIMUM_BACKOFF,
) {
    init {
        require(maximumAttempts in 1..MAXIMUM_ALLOWED_ATTEMPTS) {
            "Node delivery maximum attempts must be between 1 and $MAXIMUM_ALLOWED_ATTEMPTS."
        }
        require(!initialBackoff.isNegative()) { "Node delivery initial backoff must not be negative." }
        require(!maximumBackoff.isNegative()) { "Node delivery maximum backoff must not be negative." }
        require(maximumBackoff >= initialBackoff) {
            "Node delivery maximum backoff must be greater than or equal to initial backoff."
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_ATTEMPTS = 3
        const val MAXIMUM_ALLOWED_ATTEMPTS = 10
        val DEFAULT_INITIAL_BACKOFF = 200.milliseconds
        val DEFAULT_MAXIMUM_BACKOFF = 1_000.milliseconds
    }
}

class SignedNodeAgentTransport(
    credential: NodeAgentCredential,
    private val client: HttpClient,
    private val retryPolicy: NodeDeliveryRetryPolicy = NodeDeliveryRetryPolicy(),
    private val waitBeforeRetry: suspend (Duration) -> Unit = { delay(it) },
) : AutoCloseable {
    private val credential = validateNodeAgentCredential(credential)
    private val privateKey: PrivateKey = this.credential.privateKey()
    private val requestMutex = Mutex()
    private val nextNonces = mutableMapOf<AuthPurpose, String>()

    override fun close() {
        client.close()
    }

    suspend fun ingest(request: SnapshotIngestRequest): SnapshotIngestResponse {
        require(request.ingestId.isNotBlank()) { "Snapshot ingestId must not be blank." }
        val bodyBytes = NodeAgentJson.encodeToString(request).toByteArray(StandardCharsets.UTF_8)
        val response = executeSigned(
            operation = "Snapshot ingest",
            path = CocoaAuthProtocol.SNAPSHOT_INGEST_PATH,
            purpose = AuthPurpose.SNAPSHOT_INGEST,
            bodyBytes = bodyBytes,
        )
        return decodeRawResponse<SnapshotIngestResponse>(response, "Snapshot ingest").also {
            if (it.ingestId != request.ingestId) {
                throw NodeAgentTransportException("Snapshot ingest response ingestId does not match the request.")
            }
        }
    }

    suspend fun heartbeat(request: NodeHeartbeatRequest): NodeHeartbeatResponse {
        require(request.expectedCollectionIntervalSeconds > 0) {
            "Expected collection interval must be greater than zero."
        }
        request.error?.let {
            require(it.code.isNotBlank()) { "Heartbeat error code must not be blank." }
            require(it.message.isNotBlank()) { "Heartbeat error message must not be blank." }
        }
        val bodyBytes = NodeAgentJson.encodeToString(request).toByteArray(StandardCharsets.UTF_8)
        val response = executeSigned(
            operation = "Node heartbeat",
            path = CocoaAuthProtocol.HEARTBEAT_PATH,
            purpose = AuthPurpose.HEARTBEAT,
            bodyBytes = bodyBytes,
        )
        return decodeRawResponse(response, "Node heartbeat")
    }

    private suspend fun executeSigned(
        operation: String,
        path: String,
        purpose: AuthPurpose,
        bodyBytes: ByteArray,
    ): String = requestMutex.withLock {
        var backoff = retryPolicy.initialBackoff
        var lastFailure: NodeAgentTransportException? = null
        repeat(retryPolicy.maximumAttempts) { attempt ->
            try {
                return@withLock sendOnce(operation, path, purpose, bodyBytes)
            } catch (error: CancellationException) {
                throw error
            } catch (error: NodeAgentTransportException) {
                if (!error.retryable) throw error
                lastFailure = error
            } catch (error: IOException) {
                lastFailure = NodeAgentTransportException(
                    message = "$operation failed because of a transport error.",
                    retryable = true,
                    cause = error,
                )
            }

            if (attempt + 1 < retryPolicy.maximumAttempts) {
                waitBeforeRetry(backoff)
                backoff = minOf(backoff * 2, retryPolicy.maximumBackoff)
            }
        }
        throw checkNotNull(lastFailure)
    }

    private suspend fun sendOnce(
        operation: String,
        path: String,
        purpose: AuthPurpose,
        bodyBytes: ByteArray,
    ): String {
        val nonce = nextNonces.remove(purpose) ?: issueNonce(purpose)
        val bodySha256 = SnapshotBodyDigest.bodySha256(bodyBytes)
        val proof = SignedRequestJws.sign(
            payload = CanonicalRequest(
                method = "POST",
                path = path,
                query = "",
                purpose = purpose,
                bodySha256 = bodySha256,
            ).signedPayload(nonce),
            privateKey = privateKey,
            kid = credential.kid,
        )

        val response = client.post(credential.endpoint + path) {
            contentType(ContentType.Application.Json)
            header(CocoaAuthProtocol.CONTENT_DIGEST_HEADER, SnapshotBodyDigest.contentDigest(bodyBytes))
            header(CocoaAuthProtocol.AUTHORIZATION_HEADER, CocoaAuthorization.format(proof))
            setBody(bodyBytes)
        }
        if (response.status != HttpStatusCode.OK) {
            val failure = response.toFailureException(operation)
            val retryableNonceFailure = response.status == HttpStatusCode.Unauthorized &&
                failure.errorCode == CocoaAuthErrorCodes.NONCE_UNAVAILABLE
            throw if (retryableNonceFailure) {
                NodeAgentTransportException(
                    message = failure.message ?: "$operation nonce is unavailable.",
                    statusCode = failure.statusCode,
                    errorCode = failure.errorCode,
                    retryable = true,
                    cause = failure,
                )
            } else {
                failure
            }
        }

        cacheNextNonce(response, purpose)
        return response.bodyAsText()
    }

    private suspend fun issueNonce(purpose: AuthPurpose): String {
        val response = client.post(credential.endpoint + CocoaAuthProtocol.NONCE_PATH) {
            contentType(ContentType.Application.Json)
            setBody(
                NodeAgentJson.encodeToString(
                    NonceIssueRequest(
                        subjectType = NonceSubjectType.PRINCIPAL,
                        subjectId = credential.kid,
                        purpose = purpose,
                    ),
                ).toByteArray(StandardCharsets.UTF_8),
            )
        }
        if (response.status != HttpStatusCode.OK) throw response.toFailureException("Nonce request for $purpose")
        val nonce = decodeRawResponse<NonceIssueResponse>(response.bodyAsText(), "Nonce request for $purpose").nonce
        return validateNonce(nonce, "nonce")
    }

    private fun cacheNextNonce(
        response: HttpResponse,
        purpose: AuthPurpose,
    ) {
        val values = response.headers.getAll(CocoaAuthProtocol.NEXT_NONCE_HEADER) ?: return
        if (values.size != 1) {
            throw NodeAgentTransportException("Hub returned multiple next nonce headers.")
        }
        nextNonces[purpose] = validateNonce(values.single(), "next nonce")
    }

    private fun validateNonce(
        value: String,
        fieldName: String,
    ): String = value.also {
        runCatching { Base64Url.decodeExact(it, expectedSize = 32, fieldName = fieldName) }
            .getOrElse { error -> throw NodeAgentTransportException("Hub returned an invalid $fieldName.", cause = error) }
    }

    companion object {
        fun create(
            credential: NodeAgentCredential,
            connection: NodeAgentConnection,
            retryPolicy: NodeDeliveryRetryPolicy = NodeDeliveryRetryPolicy(),
            httpClientFactory: NodeAgentHttpClientFactory = DefaultNodeAgentHttpClientFactory,
        ): SignedNodeAgentTransport {
            val validatedCredential = validateNodeAgentCredential(credential)
            val validatedConnection = connection.validate()
            require(validatedConnection.endpoint == validatedCredential.endpoint) {
                "Node Agent connection endpoint must match the joined credential endpoint."
            }
            return SignedNodeAgentTransport(
                credential = validatedCredential,
                client = httpClientFactory.create(validatedConnection),
                retryPolicy = retryPolicy,
            )
        }
    }
}
