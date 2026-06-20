package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val AgentJson = Json {
    ignoreUnknownKeys = true
}

class AgentApiClient(
    private val httpClient: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(AgentJson)
        }
    },
) {
    suspend fun fetchLatestNodes(baseUrl: String): List<NodeSnapshot> {
        val response = httpClient.get(latestSnapshotsUrl(baseUrl))

        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val errorMessage = runCatching {
                AgentJson.decodeFromString<FailureResponse>(body).error.message
            }.getOrElse {
                body.ifBlank { response.status.description }
            }
            throw AgentApiException("Agent request failed: ${response.status.value} $errorMessage")
        }

        return response.body<LatestSnapshotsResponse>().payload.nodes
    }

    suspend fun fetchDeviceHistory(
        baseUrl: String,
        nodeId: String,
        deviceKey: String,
        limit: Int = 100,
    ): NodeDeviceHistoryPayload {
        val response = httpClient.get(deviceHistoryUrl(baseUrl, nodeId, deviceKey, limit))

        if (response.status != HttpStatusCode.OK) {
            val body = response.bodyAsText()
            val errorMessage = runCatching {
                AgentJson.decodeFromString<FailureResponse>(body).error.message
            }.getOrElse {
                body.ifBlank { response.status.description }
            }
            throw AgentApiException("Agent request failed: ${response.status.value} $errorMessage")
        }

        return response.body<DeviceHistoryResponse>().payload
    }

    private fun latestSnapshotsUrl(baseUrl: String): String {
        return URLBuilder(baseUrl.trim().trimEnd('/'))
            .appendPathSegments("api", "v1", "snapshots", "latest")
            .buildString()
    }

    private fun deviceHistoryUrl(
        baseUrl: String,
        nodeId: String,
        deviceKey: String,
        limit: Int,
    ): String {
        return URLBuilder(baseUrl.trim().trimEnd('/'))
            .appendPathSegments("api", "v1", "nodes", nodeId, "devices", deviceKey, "snapshots")
            .apply {
                parameters.append("limit", limit.toString())
                parameters.append("order", "desc")
            }
            .buildString()
    }
}

class AgentApiException(message: String) : RuntimeException(message)

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
