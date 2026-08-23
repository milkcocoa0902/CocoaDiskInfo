package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.auth.AuthorizedNonceIssuer
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationException
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationRequest
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationService
import com.milkcocoa.info.sapphire.agent.auth.NonceCapacityExceededException
import com.milkcocoa.info.sapphire.agent.auth.NonceSubjectUnavailableException
import com.milkcocoa.info.sapphire.agent.auth.NonceUnavailableException
import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.datastore.IngestIdConflictException
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalType
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertStatus
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.usecase.HubHeartbeatUseCase
import com.milkcocoa.info.sapphire.agent.usecase.HubSnapshotIngestUseCase
import com.milkcocoa.info.sapphire.agent.usecase.IngestSnapshotCommand
import com.milkcocoa.info.sapphire.agent.usecase.NodeHeartbeatCommand
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.auth.AuthProtocolException
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthErrorCodes
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import com.milkcocoa.info.sapphire.core.snapshot.SnapshotEvaluationException
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toKotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val StrictApiJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

data class HubAuthApiDependencies(
    val nonceIssuer: AuthorizedNonceIssuer,
    val registrationService: BootstrapRegistrationService,
    val signedRequestVerifier: SignedRequestVerifier,
    val snapshotIngestUseCase: HubSnapshotIngestUseCase,
    val heartbeatUseCase: HubHeartbeatUseCase,
    val nonceTtl: Duration,
    val maximumRequestBodyBytes: Long,
    val clock: Clock = Clock.systemUTC(),
)

@OptIn(ExperimentalUuidApi::class)
fun Application.installHubAuthApi(dependencies: HubAuthApiDependencies) {
    routing {
        get("/healthz") {
            call.respond(HealthResponse(status = "ready"))
        }

        post(CocoaAuthProtocol.NONCE_PATH) {
            call.handleApiErrors {
                val request = call.receiveStrictJson<NonceIssueRequest>(dependencies.maximumRequestBodyBytes)
                val issued = dependencies.nonceIssuer.issue(request, dependencies.nonceTtl)
                call.response.cacheControl(CacheControl.NoStore(null))
                call.respond(NonceIssueResponse(issued.value, issued.expiresAt))
            }
        }

        post(CocoaAuthProtocol.NODE_JOIN_PATH) {
            call.handleApiErrors {
                val request = call.receiveStrictJson<NodeJoinRequest>(dependencies.maximumRequestBodyBytes)
                val result = dependencies.registrationService.register(
                    BootstrapRegistrationRequest(
                        tokenId = request.joinTokenId.toUuid("joinTokenId"),
                        expectedTokenType = BootstrapTokenType.JOIN_TOKEN,
                        nonce = request.nonce,
                        publicKeyJwk = request.publicKey,
                        kid = request.kid,
                        displayName = request.nodeName,
                        expectedCollectionIntervalSeconds = request.expectedCollectionIntervalSeconds,
                        proof = request.proof,
                    ),
                )
                call.respond(
                    NodeJoinResponse(
                        hubId = result.hubId.toString(),
                        principalId = result.principal.principalId.toString(),
                        nodeId = checkNotNull(result.principal.nodeId).toString(),
                        kid = result.principal.kid,
                    ),
                )
            }
        }

        post(CocoaAuthProtocol.CLIENT_PAIR_PATH) {
            call.handleApiErrors {
                val request = call.receiveStrictJson<ClientPairRequest>(dependencies.maximumRequestBodyBytes)
                val result = dependencies.registrationService.register(
                    BootstrapRegistrationRequest(
                        tokenId = request.pairingTokenId.toUuid("pairingTokenId"),
                        expectedTokenType = BootstrapTokenType.PAIRING_TOKEN,
                        nonce = request.nonce,
                        publicKeyJwk = request.publicKey,
                        kid = request.kid,
                        displayName = request.displayName,
                        proof = request.proof,
                    ),
                )
                call.respond(
                    ClientPairResponse(
                        hubId = result.hubId.toString(),
                        principalId = result.principal.principalId.toString(),
                        kid = result.principal.kid,
                    ),
                )
            }
        }

        post(CocoaAuthProtocol.SNAPSHOT_INGEST_PATH) {
            call.handleApiErrors {
                val bodyBytes = call.receiveBodyBytes(dependencies.maximumRequestBodyBytes)
                val verified = dependencies.signedRequestVerifier.verify(
                    authorizationHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    expectedRequest = CanonicalRequest(
                        method = "POST",
                        path = CocoaAuthProtocol.SNAPSHOT_INGEST_PATH,
                        query = call.requireEmptyQuery(),
                        purpose = AuthPurpose.SNAPSHOT_INGEST,
                    ),
                    allowedPrincipalType = PrincipalType.NODE_AGENT,
                    bodyBytes = bodyBytes,
                    contentDigestHeaders = call.request.headers
                        .getAll(CocoaAuthProtocol.CONTENT_DIGEST_HEADER)
                        .orEmpty(),
                )
                val request = bodyBytes.decodeStrictJson<SnapshotIngestRequest>()
                val ingestId = request.ingestId.toUuid("ingestId")
                dependencies.signedRequestVerifier.consumeNonce(verified)

                val receivedAt = dependencies.clock.instant()
                val result = dependencies.snapshotIngestUseCase.ingest(
                    IngestSnapshotCommand(
                        ingestId = ingestId,
                        origin = SnapshotOrigin(
                            nodeId = checkNotNull(verified.principal.nodeId),
                            nodeName = verified.principal.displayName,
                        ),
                        snapshot = request.snapshot,
                        receivedAt = receivedAt,
                    ),
                )
                call.respondWithNextNonce(
                    verifier = dependencies.signedRequestVerifier,
                    nonceTtl = dependencies.nonceTtl,
                    verified = verified,
                    response = SnapshotIngestResponse(
                        ingestId = result.ingestId.toString(),
                        status = when (result.status) {
                            SnapshotInsertStatus.STORED -> SnapshotIngestStatus.STORED
                            SnapshotInsertStatus.DUPLICATE -> SnapshotIngestStatus.DUPLICATE
                        },
                        snapshotId = result.snapshotId.toString(),
                        receivedAt = result.receivedAt.toKotlinInstant(),
                    ),
                )
            }
        }

        post(CocoaAuthProtocol.HEARTBEAT_PATH) {
            call.handleApiErrors {
                val bodyBytes = call.receiveBodyBytes(dependencies.maximumRequestBodyBytes)
                val verified = dependencies.signedRequestVerifier.verify(
                    authorizationHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    expectedRequest = CanonicalRequest(
                        method = "POST",
                        path = CocoaAuthProtocol.HEARTBEAT_PATH,
                        query = call.requireEmptyQuery(),
                        purpose = AuthPurpose.HEARTBEAT,
                    ),
                    allowedPrincipalType = PrincipalType.NODE_AGENT,
                    bodyBytes = bodyBytes,
                    contentDigestHeaders = call.request.headers
                        .getAll(CocoaAuthProtocol.CONTENT_DIGEST_HEADER)
                        .orEmpty(),
                )
                val request = bodyBytes.decodeStrictJson<NodeHeartbeatRequest>()
                if (request.expectedCollectionIntervalSeconds <= 0) {
                    throw RequestValidationException(
                        "collection_interval_invalid",
                        "Expected collection interval must be greater than zero.",
                    )
                }
                dependencies.signedRequestVerifier.consumeNonce(verified)

                val receivedAt = dependencies.clock.instant()
                dependencies.heartbeatUseCase.record(
                    NodeHeartbeatCommand(
                        nodeId = checkNotNull(verified.principal.nodeId),
                        expectedCollectionIntervalSeconds = request.expectedCollectionIntervalSeconds,
                        receivedAt = receivedAt,
                        errorCode = request.error?.code,
                        errorMessage = request.error?.message,
                    ),
                )
                call.respondWithNextNonce(
                    verifier = dependencies.signedRequestVerifier,
                    nonceTtl = dependencies.nonceTtl,
                    verified = verified,
                    response = NodeHeartbeatResponse(receivedAt.toKotlinInstant()),
                )
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.receiveStrictJson(maximumBytes: Long): T =
    receiveBodyBytes(maximumBytes).decodeStrictJson()

internal suspend fun ApplicationCall.receiveBodyBytes(maximumBytes: Long): ByteArray {
    if (!request.headers.getAll(HttpHeaders.ContentEncoding).isNullOrEmpty()) {
        throw UnsupportedContentEncodingException()
    }
    val announcedSize = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
    if (announcedSize != null && announcedSize > maximumBytes) throw RequestBodyTooLargeException()
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(REQUEST_BODY_BUFFER_BYTES)
    var receivedBytes = 0L
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        receivedBytes += read
        if (receivedBytes > maximumBytes) throw RequestBodyTooLargeException()
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private inline fun <reified T> ByteArray.decodeStrictJson(): T = try {
    StrictApiJson.decodeFromString<T>(decodeToString(throwOnInvalidSequence = true))
} catch (error: SerializationException) {
    throw MalformedRequestException(error)
} catch (error: IllegalArgumentException) {
    throw MalformedRequestException(error)
}

private fun ApplicationCall.requireEmptyQuery(): String {
    if (!request.queryParameters.isEmpty()) {
        throw RequestValidationException("query_not_allowed", "This endpoint does not accept query parameters.")
    }
    return ""
}

internal suspend inline fun <reified T : Any> ApplicationCall.respondWithNextNonce(
    verifier: SignedRequestVerifier,
    nonceTtl: Duration,
    verified: com.milkcocoa.info.sapphire.agent.auth.VerifiedPrincipalRequest,
    response: T,
) {
    val next = verifier.issueNextNonce(verified, nonceTtl)
    this.response.header(CocoaAuthProtocol.NEXT_NONCE_HEADER, next.value)
    respond(response)
}

internal suspend fun ApplicationCall.handleApiErrors(block: suspend () -> Unit) {
    try {
        block()
    } catch (error: NonceUnavailableException) {
        respondFailure(HttpStatusCode.Unauthorized, CocoaAuthErrorCodes.NONCE_UNAVAILABLE, error.message)
    } catch (error: NonceSubjectUnavailableException) {
        respondFailure(HttpStatusCode.Unauthorized, "nonce_subject_unavailable", error.message)
    } catch (error: NonceCapacityExceededException) {
        respondFailure(HttpStatusCode.TooManyRequests, "nonce_capacity_exceeded", error.message)
    } catch (error: BootstrapRegistrationException) {
        val status = when (error.code) {
            "bootstrap_token_reused" -> HttpStatusCode.Conflict
            "bootstrap_name_mismatch", "bootstrap_name_invalid", "collection_interval_invalid",
            "collection_interval_unexpected", "bootstrap_kid_invalid" -> HttpStatusCode.BadRequest
            else -> HttpStatusCode.Unauthorized
        }
        respondFailure(status, error.code, error.message)
    } catch (error: IngestIdConflictException) {
        respondFailure(HttpStatusCode.Conflict, "ingest_id_conflict", error.message)
    } catch (error: RequestBodyTooLargeException) {
        respondFailure(HttpStatusCode.PayloadTooLarge, "request_body_too_large", error.message)
    } catch (error: MalformedRequestException) {
        respondFailure(HttpStatusCode.BadRequest, "request_invalid", error.message)
    } catch (error: RequestValidationException) {
        respondFailure(HttpStatusCode.BadRequest, error.code, error.message)
    } catch (error: AuthProtocolException) {
        respondFailure(HttpStatusCode.Unauthorized, "authentication_invalid", error.message)
    } catch (error: UnsupportedContentEncodingException) {
        respondFailure(HttpStatusCode.UnsupportedMediaType, "content_encoding_not_supported", error.message)
    } catch (error: SnapshotEvaluationException) {
        respondFailure(
            HttpStatusCode.InternalServerError,
            "health_policy_evaluation_failed",
            "Configured health policy could not evaluate a stored snapshot.",
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        respondFailure(HttpStatusCode.InternalServerError, "internal_error", "Internal server error.")
    }
}

internal suspend fun ApplicationCall.respondFailure(
    status: HttpStatusCode,
    code: String,
    message: String?,
) {
    respond(
        status,
        ApiResponse.Failure(
            ApiError(
                code = code,
                message = message ?: status.description,
            ),
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun String.toUuid(fieldName: String): Uuid = runCatching { Uuid.parse(this) }
    .getOrElse { throw RequestValidationException("${fieldName}_invalid", "$fieldName must be a UUID.") }

private class RequestBodyTooLargeException : IllegalArgumentException("Request body exceeds the configured limit.")

private class UnsupportedContentEncodingException : IllegalArgumentException(
    "Content-Encoding is not supported for API request bodies.",
)

private class MalformedRequestException(cause: Throwable) : IllegalArgumentException(
    "Request body must be valid UTF-8 JSON with the required shape.",
    cause,
)

internal class RequestValidationException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

@Serializable
private data class HealthResponse(
    val status: String,
)

private const val REQUEST_BODY_BUFFER_BYTES = 8 * 1024
