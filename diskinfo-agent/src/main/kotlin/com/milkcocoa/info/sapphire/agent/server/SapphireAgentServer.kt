package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.ApiResponse
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
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

class SapphireAgentServer(
    private val repository: DiskSnapshotRepository = DiskSnapshotRepository(),
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
            installSapphireAgentApi(repository)
            module()
        }.start(wait = wait)
    }
}

fun Application.installSapphireAgentApi(
    repository: DiskSnapshotRepository = DiskSnapshotRepository(),
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
                        nodes = repository.findLatestNodes(),
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

@Serializable
@Resource("/api/v1/snapshots/latest")
private class LatestSnapshotsResource

@Serializable
@Resource("/api/v1/devices/{deviceKey}/snapshots/latest")
private class LatestDeviceSnapshotResource(
    val deviceKey: String,
)
