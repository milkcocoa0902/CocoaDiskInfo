package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.auth.AuthorizedNonceIssuer
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationRequest
import com.milkcocoa.info.sapphire.agent.auth.BootstrapRegistrationService
import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.datastore.BootstrapTokenType
import com.milkcocoa.info.sapphire.agent.usecase.LatestSnapshotsQueryService
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalType
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequestCodec
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import com.milkcocoa.info.sapphire.core.auth.NonceIssueRequest
import com.milkcocoa.info.sapphire.core.auth.NonceIssueResponse
import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val StandaloneBootstrapJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

data class StandaloneAuthDependencies(
    val nonceIssuer: AuthorizedNonceIssuer,
    val registrationService: BootstrapRegistrationService,
    val signedRequestVerifier: SignedRequestVerifier,
    val queryService: LatestSnapshotsQueryService,
    val snapshotUseCase: SnapshotUseCase,
    val nonceTtl: Duration,
    val maximumRequestBodyBytes: Long,
)

@OptIn(ExperimentalUuidApi::class)
fun Application.installStandaloneSecureApi(dependencies: StandaloneAuthDependencies) {
    routing {
        post(CocoaAuthProtocol.NONCE_PATH) {
            call.handleApiErrors {
                val request = call.receiveStandaloneJson<NonceIssueRequest>(dependencies.maximumRequestBodyBytes)
                val issued = dependencies.nonceIssuer.issue(request, dependencies.nonceTtl)
                call.response.cacheControl(CacheControl.NoStore(null))
                call.respond(NonceIssueResponse(issued.value, issued.expiresAt))
            }
        }
        post(CocoaAuthProtocol.CLIENT_PAIR_PATH) {
            call.handleApiErrors {
                val request = call.receiveStandaloneJson<ClientPairRequest>(dependencies.maximumRequestBodyBytes)
                val result = dependencies.registrationService.register(
                    BootstrapRegistrationRequest(
                        tokenId = runCatching { Uuid.parse(request.pairingTokenId) }
                            .getOrElse {
                                throw RequestValidationException(
                                    "pairingTokenId_invalid",
                                    "pairingTokenId must be a UUID.",
                                )
                            },
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
        get("/api/v1/devices/{deviceKey}/snapshots/latest") {
            call.handleApiErrors {
                if (!call.request.queryParameters.isEmpty()) {
                    throw RequestValidationException("query_not_allowed", "This endpoint does not accept query parameters.")
                }
                val deviceKey = call.parameters["deviceKey"]?.takeIf(String::isNotBlank)
                    ?: throw RequestValidationException("device_key_required", "Device key is required.")
                val path = CanonicalRequestCodec.path(
                    "api", "v1", "devices", deviceKey, "snapshots", "latest",
                )
                val verified = dependencies.signedRequestVerifier.verify(
                    authorizationHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    expectedRequest = CanonicalRequest("GET", path, "", AuthPurpose.CLIENT_READ),
                    allowedPrincipalType = PrincipalType.CLIENT,
                    contentDigestHeaders = call.request.headers
                        .getAll(CocoaAuthProtocol.CONTENT_DIGEST_HEADER)
                        .orEmpty(),
                )
                dependencies.signedRequestVerifier.consumeNonce(verified)
                val snapshot = dependencies.snapshotUseCase.findLatestByDeviceKey(deviceKey)
                if (snapshot == null) {
                    call.respondFailure(
                        io.ktor.http.HttpStatusCode.NotFound,
                        "snapshot_not_found",
                        "No snapshot history found for the requested device.",
                    )
                    return@handleApiErrors
                }
                call.respondWithNextNonce(
                    dependencies.signedRequestVerifier,
                    dependencies.nonceTtl,
                    verified,
                    ApiResponse.Success(snapshot),
                )
            }
        }
    }
    installHubReadApi(
        HubReadApiDependencies(
            queryService = dependencies.queryService,
            signedRequestVerifier = dependencies.signedRequestVerifier,
            nonceTtl = dependencies.nonceTtl,
        ),
    )
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.receiveStandaloneJson(
    maximumBytes: Long,
): T {
    val bodyBytes = receiveBodyBytes(maximumBytes)
    return try {
        StandaloneBootstrapJson.decodeFromString<T>(bodyBytes.decodeToString(throwOnInvalidSequence = true))
    } catch (error: SerializationException) {
        throw RequestValidationException("request_invalid", "Request body must match the required JSON shape.")
    } catch (error: IllegalArgumentException) {
        throw RequestValidationException("request_invalid", "Request body must be valid UTF-8 JSON.")
    }
}
