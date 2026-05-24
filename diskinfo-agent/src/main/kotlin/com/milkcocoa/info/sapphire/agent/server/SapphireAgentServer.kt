package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun Application.installSapphireAgentApi(
    repository: DiskSnapshotRepository = DiskSnapshotRepository(),
) {
    install(ContentNegotiation) {
        json(Json)
    }

    routing {
        route("/api/v1") {
            get("/snapshots/latest") {
                call.respond(
                    ApiResponse.Success(
                        LatestSnapshotsPayload(
                            snapshots = repository.findLatestForEachDevice(),
                        ),
                    ),
                )
            }

            get("/devices/{deviceKey}/snapshots/latest") {
                val deviceKey = call.parameters["deviceKey"]
                if (deviceKey.isNullOrBlank()) {
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

                val snapshot = repository.findLatestByDeviceKey(deviceKey)
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
        }
    }
}
