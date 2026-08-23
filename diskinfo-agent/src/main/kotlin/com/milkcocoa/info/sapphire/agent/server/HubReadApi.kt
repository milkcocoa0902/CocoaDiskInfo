package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.auth.SignedRequestVerifier
import com.milkcocoa.info.sapphire.agent.datastore.HistoryOrder
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotCursor
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.PrincipalType
import com.milkcocoa.info.sapphire.agent.usecase.LatestSnapshotsQueryService
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.auth.AuthPurpose
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequest
import com.milkcocoa.info.sapphire.core.auth.CanonicalRequestCodec
import com.milkcocoa.info.sapphire.core.auth.CocoaAuthProtocol
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class HubReadApiDependencies(
    val queryService: LatestSnapshotsQueryService,
    val signedRequestVerifier: SignedRequestVerifier,
    val nonceTtl: Duration,
)

@OptIn(ExperimentalUuidApi::class)
fun Application.installHubReadApi(dependencies: HubReadApiDependencies) {
    routing {
        get("/api/v1/snapshots/latest") {
            call.handleApiErrors {
                val rawQuery = call.rawQueryParameters()
                validateKnownQuery(rawQuery, setOf("cursor", "limit"))
                val limitValue = rawQuery.singleValue("limit")
                val limit = limitValue?.toIntOrNull() ?: LatestSnapshotPageRequest.DEFAULT_LIMIT
                if ((limitValue != null && limitValue.toIntOrNull() == null) ||
                    limit !in 1..LatestSnapshotPageRequest.MAX_LIMIT
                ) {
                    throw RequestValidationException(
                        "latest_limit_invalid",
                        "Latest snapshot limit must be between 1 and ${LatestSnapshotPageRequest.MAX_LIMIT}.",
                    )
                }
                val cursorValue = rawQuery.singleValue("cursor")
                val cursor = cursorValue?.let {
                    runCatching { LatestPageCursorCodec.decode(it) }
                        .getOrElse { error ->
                            throw RequestValidationException("invalid_cursor", error.message ?: "Cursor is invalid.")
                        }
                }
                val canonicalCursor = cursor?.let(LatestPageCursorCodec::encode)
                val canonicalQuery = CanonicalRequestCodec.query(
                    mapOf(
                        "cursor" to canonicalCursor,
                        "limit" to limit.toString(),
                    ),
                )
                val verified = dependencies.signedRequestVerifier.verify(
                    authorizationHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    expectedRequest = CanonicalRequest(
                        method = "GET",
                        path = "/api/v1/snapshots/latest",
                        query = canonicalQuery,
                        purpose = AuthPurpose.CLIENT_READ,
                    ),
                    allowedPrincipalType = PrincipalType.CLIENT,
                    contentDigestHeaders = call.request.headers
                        .getAll(CocoaAuthProtocol.CONTENT_DIGEST_HEADER)
                        .orEmpty(),
                )
                dependencies.signedRequestVerifier.consumeNonce(verified)

                val result = dependencies.queryService.findLatestPage(
                    LatestSnapshotPageRequest(
                        cursor = cursor?.let { LatestSnapshotCursor(it.nodeId, it.deviceKey) },
                        limit = limit,
                    ),
                )
                val nextCursor = result.nextCursor?.let {
                    LatestPageCursorCodec.encode(LatestPageCursor(it.nodeId, it.deviceKey))
                }
                val payload = result.payload.copy(
                    pagination = checkNotNull(result.payload.pagination).copy(nextCursor = nextCursor),
                )
                call.respondWithNextNonce(
                    verifier = dependencies.signedRequestVerifier,
                    nonceTtl = dependencies.nonceTtl,
                    verified = verified,
                    response = ApiResponse.Success(payload),
                )
            }
        }

        get("/api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots/latest") {
            call.handleApiErrors {
                val nodeId = call.requireNodeId()
                val deviceKey = call.requireDeviceKey()
                val rawQuery = call.rawQueryParameters()
                validateKnownQuery(rawQuery, emptySet())
                val path = CanonicalRequestCodec.path(
                    "api", "v1", "nodes", nodeId.toString(), "devices", deviceKey, "snapshots", "latest",
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
                val result = dependencies.queryService.findNodeLatest(nodeId, deviceKey)
                if (result == null || result.snapshot == null) {
                    call.respondFailure(
                        HttpStatusCode.NotFound,
                        "snapshot_not_found",
                        "No snapshot history found for the requested node and device.",
                    )
                    return@handleApiErrors
                }
                val freshness = result.freshness
                val payload = LatestSnapshotsPayload(
                    nodes = listOf(
                        NodeSnapshot(
                            nodeId = freshness.nodeId,
                            nodeName = freshness.nodeName,
                            devices = listOf(result.snapshot),
                            status = freshness.status,
                            lastSeenAt = freshness.lastSeenAt,
                            deviceStates = listOf(freshness.deviceState),
                        ),
                    ),
                    partial = freshness.snapshotMissing || freshness.deviceState.stale || freshness.currentError != null,
                    errors = listOfNotNull(freshness.currentError),
                    evaluationPolicy = result.snapshot.evaluationPolicy,
                )
                call.respondWithNextNonce(
                    dependencies.signedRequestVerifier,
                    dependencies.nonceTtl,
                    verified,
                    ApiResponse.Success(payload),
                )
            }
        }

        get("/api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots") {
            call.handleApiErrors {
                val nodeId = call.requireNodeId()
                val deviceKey = call.requireDeviceKey()
                val rawQuery = call.rawQueryParameters()
                validateKnownQuery(rawQuery, setOf("from", "limit", "order", "to"))
                val query = rawQuery.toHistoryQuery()
                val canonicalQuery = CanonicalRequestCodec.query(
                    mapOf(
                        "from" to query.from?.toInstant()?.toString(),
                        "limit" to query.limit.toString(),
                        "order" to query.order.name.lowercase(),
                        "to" to query.to?.toInstant()?.toString(),
                    ),
                )
                val path = CanonicalRequestCodec.path(
                    "api", "v1", "nodes", nodeId.toString(), "devices", deviceKey, "snapshots",
                )
                val verified = dependencies.signedRequestVerifier.verify(
                    authorizationHeaders = call.request.headers.getAll(HttpHeaders.Authorization).orEmpty(),
                    expectedRequest = CanonicalRequest("GET", path, canonicalQuery, AuthPurpose.CLIENT_READ),
                    allowedPrincipalType = PrincipalType.CLIENT,
                    contentDigestHeaders = call.request.headers
                        .getAll(CocoaAuthProtocol.CONTENT_DIGEST_HEADER)
                        .orEmpty(),
                )
                dependencies.signedRequestVerifier.consumeNonce(verified)
                val result = dependencies.queryService.findNodeHistory(nodeId, deviceKey, query)
                if (result == null) {
                    call.respondFailure(HttpStatusCode.NotFound, "node_not_found", "Node was not found.")
                    return@handleApiErrors
                }
                val freshness = result.freshness
                val payload = result.payload.copy(
                    status = freshness.status,
                    lastSeenAt = freshness.lastSeenAt,
                    deviceState = freshness.deviceState,
                    currentError = freshness.currentError,
                )
                call.respondWithNextNonce(
                    dependencies.signedRequestVerifier,
                    dependencies.nonceTtl,
                    verified,
                    ApiResponse.Success(payload),
                )
            }
        }
    }
}

private fun ApplicationCall.rawQueryParameters(): List<Pair<String, String>> =
    request.queryParameters.entries().flatMap { (name, values) ->
        values.map { value -> name to value }
    }

private fun validateKnownQuery(
    rawQuery: List<Pair<String, String>>,
    knownNames: Set<String>,
) {
    runCatching { CanonicalRequestCodec.validateRawQueryShape(rawQuery, knownNames) }
        .getOrElse { error ->
            throw RequestValidationException("query_invalid", error.message ?: "Query parameters are invalid.")
        }
}

private fun List<Pair<String, String>>.singleValue(name: String): String? =
    firstOrNull { it.first == name }?.second

private fun List<Pair<String, String>>.toHistoryQuery(): HistoryQuery {
    val limitValue = singleValue("limit")
    val limit = limitValue?.toIntOrNull() ?: 100
    if ((limitValue != null && limitValue.toIntOrNull() == null) || limit !in 1..1000) {
        throw RequestValidationException("history_limit_invalid", "History limit must be between 1 and 1000.")
    }
    val from = singleValue("from")?.parseInstant("from")
    val to = singleValue("to")?.parseInstant("to")
    if (from != null && to != null && from.isAfter(to)) {
        throw RequestValidationException(
            "history_time_range_invalid",
            "History 'from' timestamp must be earlier than or equal to 'to'.",
        )
    }
    val order = when (singleValue("order")?.lowercase()) {
        null, "desc" -> HistoryOrder.DESC
        "asc" -> HistoryOrder.ASC
        else -> throw RequestValidationException("history_order_invalid", "History order must be 'asc' or 'desc'.")
    }
    return HistoryQuery(limit = limit, from = from, to = to, order = order)
}

private fun String.parseInstant(name: String): OffsetDateTime = try {
    OffsetDateTime.ofInstant(Instant.parse(this), ZoneOffset.UTC)
} catch (_: DateTimeParseException) {
    throw RequestValidationException("history_timestamp_invalid", "History '$name' must be an ISO-8601 instant.")
}

@OptIn(ExperimentalUuidApi::class)
private fun ApplicationCall.requireNodeId(): Uuid {
    val value = parameters["nodeId"].orEmpty()
    return runCatching {
        val uuid = UUID.fromString(value)
        Uuid.fromLongs(uuid.mostSignificantBits, uuid.leastSignificantBits)
    }.getOrElse { throw RequestValidationException("node_id_invalid", "Node id must be a UUID.") }
}

private fun ApplicationCall.requireDeviceKey(): String = parameters["deviceKey"]
    ?.takeIf(String::isNotBlank)
    ?: throw RequestValidationException("device_key_required", "Device key is required.")
