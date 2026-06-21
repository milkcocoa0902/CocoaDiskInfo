package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.datastore.DEFAULT_HISTORY_LIMIT
import com.milkcocoa.info.sapphire.agent.datastore.HistoryOrder
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.MAX_HISTORY_LIMIT
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class SapphireAgentServer(
    private val snapshotUseCase: SnapshotUseCase,
    private val host: String = "127.0.0.1",
    private val port: Int = 14631,
) {
    fun start(
        wait: Boolean = true,
        module: Application.() -> Unit = {},
    ) {
        embeddedServer(
            factory = CIO,
            host = host,
            port = port,
        ) {
            installSapphireAgentApi(snapshotUseCase)
            module()
        }.start(wait = wait)
    }
}

@OptIn(ExperimentalUuidApi::class)
fun Application.installSapphireAgentApi(
    snapshotUseCase: SnapshotUseCase,
) {
    install(ContentNegotiation) {
        json(Json)
    }
    install(Resources)

    routing {
        get<LatestSnapshotsResource> {
            call.respond(
                ApiResponse.Success(
                    LatestSnapshotsPayload(
                        nodes = snapshotUseCase.findLatestNodes(),
                    ),
                ),
            )
        }

        get<LatestDeviceSnapshotResource> { resource ->
            if (resource.deviceKey.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.Failure(
                        ApiError(
                            code = "device_key_required",
                            message = "Device key is required.",
                        ),
                    ),
                )
                return@get
            }

            val deviceKey = resource.deviceKey
            val snapshot = snapshotUseCase.findLatestByDeviceKey(deviceKey)
            if (snapshot == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse.Failure(
                        ApiError(
                            code = "snapshot_not_found",
                            message = "No snapshot history found for deviceKey=$deviceKey.",
                        ),
                    ),
                )
                return@get
            }

            call.respond(ApiResponse.Success(snapshot))
        }

        get<DeviceHistoryResource> { resource ->
            val nodeId = resource.nodeId.toNodeUuid()
            when {
                resource.nodeId.isBlank() -> {
                    call.respondBadRequest("node_id_required", "Node id is required.")
                    return@get
                }

                nodeId == null -> {
                    call.respondBadRequest("node_id_invalid", "Node id must be a UUID.")
                    return@get
                }

                resource.deviceKey.isBlank() -> {
                    call.respondBadRequest("device_key_required", "Device key is required.")
                    return@get
                }
            }

            val query = resource.toHistoryQueryOrRespondBadRequest(call) ?: return@get

            call.respond(
                ApiResponse.Success(
                    snapshotUseCase.findHistory(
                        nodeId = nodeId,
                        deviceKey = resource.deviceKey,
                        query = query,
                    ),
                ),
            )
        }
    }
}

@Serializable
@Resource("/api/v1/snapshots/latest")
private class LatestSnapshotsResource

@Serializable
@Resource("/api/v1/devices/{deviceKey}/snapshots/latest")
private class LatestDeviceSnapshotResource(
    val deviceKey: String,
)

@Serializable
@Resource("/api/v1/nodes/{nodeId}/devices/{deviceKey}/snapshots")
private class DeviceHistoryResource(
    val nodeId: String,
    val deviceKey: String,
    val limit: String? = null,
    val from: String? = null,
    val to: String? = null,
    val order: String? = null,
)

private suspend fun DeviceHistoryResource.toHistoryQueryOrRespondBadRequest(
    call: ApplicationCall,
): HistoryQuery? {
    val parsedLimit = limit?.toIntOrNull()
    val effectiveLimit = parsedLimit ?: DEFAULT_HISTORY_LIMIT
    if ((limit != null && parsedLimit == null) || effectiveLimit !in 1..MAX_HISTORY_LIMIT) {
        call.respondBadRequest(
            code = "history_limit_invalid",
            message = "History limit must be between 1 and $MAX_HISTORY_LIMIT.",
        )
        return null
    }

    val parsedFrom = if (from == null) {
        null
    } else {
        from.toOffsetDateTimeOrRespondBadRequest(call, "from") ?: return null
    }
    val parsedTo = if (to == null) {
        null
    } else {
        to.toOffsetDateTimeOrRespondBadRequest(call, "to") ?: return null
    }
    if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
        call.respondBadRequest(
            code = "history_time_range_invalid",
            message = "History 'from' timestamp must be earlier than or equal to 'to'.",
        )
        return null
    }

    val parsedOrder = when (order?.lowercase()) {
        null, "desc" -> HistoryOrder.DESC
        "asc" -> HistoryOrder.ASC
        else -> {
            call.respondBadRequest(
                code = "history_order_invalid",
                message = "History order must be 'asc' or 'desc'.",
            )
            return null
        }
    }

    return HistoryQuery(
        limit = effectiveLimit,
        from = parsedFrom,
        to = parsedTo,
        order = parsedOrder,
    )
}

private suspend fun String.toOffsetDateTimeOrRespondBadRequest(
    call: ApplicationCall,
    parameterName: String,
): OffsetDateTime? {
    return try {
        OffsetDateTime.ofInstant(Instant.parse(this), ZoneOffset.UTC)
    } catch (_: DateTimeParseException) {
        call.respondBadRequest(
            code = "history_timestamp_invalid",
            message = "History '$parameterName' must be an ISO-8601 instant.",
        )
        null
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun String.toNodeUuid(): Uuid? {
    return runCatching {
        val uuid = UUID.fromString(this)
        Uuid.fromLongs(uuid.mostSignificantBits, uuid.leastSignificantBits)
    }.getOrNull()
}

private suspend fun ApplicationCall.respondBadRequest(
    code: String,
    message: String,
) {
    respond(
        HttpStatusCode.BadRequest,
        ApiResponse.Failure(
            ApiError(
                code = code,
                message = message,
            ),
        ),
    )
}
