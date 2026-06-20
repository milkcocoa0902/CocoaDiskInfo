package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.insertDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

private val TestJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalUuidApi::class)
class SapphireAgentServerTest {
    @Test
    fun `history endpoint returns selected node device snapshots`() = testApplication {
        connectDiskSnapshotTestDatabase()
        val nodeId = testNodeId(1)
        val otherNodeId = testNodeId(2)
        val deviceKey = "serial-a"

        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 1_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot(deviceKey, timestampMillis = 2_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot("serial-b", timestampMillis = 3_000))
        insertDiskSnapshot(otherNodeId, "node-b", testDiskSnapshot(deviceKey, timestampMillis = 4_000))

        application {
            installSapphireAgentApi(DiskSnapshotRepository())
        }

        val response = client.get(
            "/api/v1/nodes/$nodeId/devices/$deviceKey/snapshots?limit=2&order=asc",
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = TestJson.decodeFromString<HistorySuccessResponse>(response.bodyAsText()).payload
        assertEquals(nodeId.toString(), payload.nodeId)
        assertEquals("node-a", payload.nodeName)
        assertEquals(deviceKey, payload.deviceKey)
        assertEquals(listOf(1_000L, 2_000L), payload.snapshots.map { it.timestamp.toEpochMilliseconds() })
    }

    @Test
    fun `history endpoint returns empty snapshots for unknown node device`() = testApplication {
        connectDiskSnapshotTestDatabase()
        val nodeId = testNodeId(1)

        application {
            installSapphireAgentApi(DiskSnapshotRepository())
        }

        val response = client.get("/api/v1/nodes/$nodeId/devices/missing/snapshots")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = TestJson.decodeFromString<HistorySuccessResponse>(response.bodyAsText()).payload
        assertEquals(nodeId.toString(), payload.nodeId)
        assertEquals("", payload.nodeName)
        assertEquals("missing", payload.deviceKey)
        assertEquals(emptyList(), payload.snapshots)
    }

    @Test
    fun `history endpoint rejects invalid path and query values`() = testApplication {
        connectDiskSnapshotTestDatabase()
        val nodeId = testNodeId(1)

        application {
            installSapphireAgentApi(DiskSnapshotRepository())
        }

        val invalidUrls = listOf(
            "/api/v1/nodes/not-a-uuid/devices/serial-a/snapshots",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?limit=0",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?limit=1001",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?limit=abc",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?from=not-a-time",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?to=not-a-time",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?order=sideways",
            "/api/v1/nodes/$nodeId/devices/serial-a/snapshots?from=1970-01-01T00:00:03Z&to=1970-01-01T00:00:02Z",
        )

        invalidUrls.forEach { url ->
            val response = client.get(url)

            assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for $url")
            TestJson.decodeFromString<FailureResponse>(response.bodyAsText())
        }
    }
}

@Serializable
private data class HistorySuccessResponse(
    val payload: NodeDeviceHistoryPayload,
)

@Serializable
private data class FailureResponse(
    val error: ApiError,
)
