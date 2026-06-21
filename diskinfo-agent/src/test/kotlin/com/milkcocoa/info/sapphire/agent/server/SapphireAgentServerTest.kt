package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryOrder
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val TestJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalUuidApi::class)
class SapphireAgentServerTest {
    private val deviceKeyA = "b25b5b07-5629-5c33-89ba-1ef17c03cc0c"

    @Test
    fun `history endpoint returns selected node device snapshots`() = testApplication {
        val nodeId = testNodeId(1)
        val deviceKey = deviceKeyA
        val repository = FakeDiskSnapshotRepository(
            historyPayload = NodeDeviceHistoryPayload(
                nodeId = nodeId.toString(),
                nodeName = "node-a",
                deviceKey = deviceKey,
                snapshots = listOf(
                    testDiskSnapshot(deviceKey, timestampMillis = 1_000),
                    testDiskSnapshot(deviceKey, timestampMillis = 2_000),
                ),
            ),
        )

        application {
            installSapphireAgentApi(repository)
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
        assertEquals(
            listOf(HistoryRequest(nodeId.toString(), deviceKey, HistoryQuery(limit = 2, order = HistoryOrder.ASC))),
            repository.historyRequests,
        )
    }

    @Test
    fun `history endpoint returns empty snapshots for unknown node device`() = testApplication {
        val nodeId = testNodeId(1)
        val repository = FakeDiskSnapshotRepository(
            historyPayload = NodeDeviceHistoryPayload(
                nodeId = nodeId.toString(),
                nodeName = "",
                deviceKey = "missing",
                snapshots = emptyList(),
            ),
        )

        application {
            installSapphireAgentApi(repository)
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
        val nodeId = testNodeId(1)
        val repository = FakeDiskSnapshotRepository()

        application {
            installSapphireAgentApi(repository)
        }

        val invalidUrls = listOf(
            "/api/v1/nodes/not-a-uuid/devices/$deviceKeyA/snapshots",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?limit=0",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?limit=1001",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?limit=abc",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?from=not-a-time",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?to=not-a-time",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?order=sideways",
            "/api/v1/nodes/$nodeId/devices/$deviceKeyA/snapshots?from=1970-01-01T00:00:03Z&to=1970-01-01T00:00:02Z",
        )

        invalidUrls.forEach { url ->
            val response = client.get(url)

            assertEquals(HttpStatusCode.BadRequest, response.status, "Expected 400 for $url")
            TestJson.decodeFromString<FailureResponse>(response.bodyAsText())
        }
        assertEquals(emptyList(), repository.historyRequests)
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

private data class HistoryRequest(
    val nodeId: String,
    val deviceKey: String,
    val query: HistoryQuery,
)

private class FakeDiskSnapshotRepository(
    private val historyPayload: NodeDeviceHistoryPayload = NodeDeviceHistoryPayload(
        nodeId = "",
        nodeName = "",
        deviceKey = "",
        snapshots = emptyList(),
    ),
) : DiskSnapshotRepository {
    val historyRequests = mutableListOf<HistoryRequest>()

    override fun insert(snapshot: DiskSnapshot) = Unit

    override fun findLatestNodes(): List<NodeSnapshot> = emptyList()

    override fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = null

    @OptIn(ExperimentalUuidApi::class)
    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload {
        historyRequests += HistoryRequest(
            nodeId = nodeId.toString(),
            deviceKey = deviceKey,
            query = query,
        )
        return historyPayload
    }
}
