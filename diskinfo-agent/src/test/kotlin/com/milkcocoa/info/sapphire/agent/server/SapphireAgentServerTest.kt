package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.datastore.HistoryOrder
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.core.api.ApiError
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val TestJson = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalUuidApi::class)
class SapphireAgentServerTest {
    private val deviceKeyA = "b25b5b07-5629-5c33-89ba-1ef17c03cc0c"

    @Test
    fun `latest snapshots endpoint returns latest snapshot for each node`() = testApplication {
        val snapshot = testDiskSnapshot(deviceKeyA, timestampMillis = 1_000)
        val snapshotUseCase = FakeSnapshotUseCase(
            latestPage = LatestSnapshotPage(
                rows = listOf(
                    StoredDiskSnapshot(
                        snapshotId = testNodeId(2),
                        ingestId = testNodeId(3),
                        origin = SnapshotOrigin(testNodeId(1), "node-a"),
                        snapshot = snapshot,
                        receivedAt = Instant.EPOCH,
                    ),
                ),
                hasMore = true,
                nextCursor = null,
            ),
        )

        application {
            installSapphireAgentApi(snapshotUseCase)
        }

        val response = client.get("/api/v1/snapshots/latest")

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = TestJson.decodeFromString<LatestSnapshotsSuccessResponse>(response.bodyAsText()).payload
        assertEquals(1, payload.nodes.size)
        assertEquals("node-a", payload.nodes.single().nodeName)
        assertEquals(snapshot.timestamp, payload.nodes.single().devices.single().timestamp)
        assertEquals(snapshot.health, payload.nodes.single().devices.single().reportedHealth)
        assertEquals("default", payload.evaluationPolicy?.policyName)
        assertEquals(
            listOf(LatestSnapshotPageRequest(limit = LatestSnapshotPageRequest.MAX_LIMIT)),
            snapshotUseCase.latestPageRequests,
        )
        assertEquals(true, payload.pagination?.hasMore)
    }

    @Test
    fun `latest device endpoint returns snapshot and reports missing device`() = testApplication {
        val snapshot = testDiskSnapshot(deviceKeyA, timestampMillis = 1_000)
        val snapshotUseCase = FakeSnapshotUseCase(
            latestByDeviceKey = mapOf(deviceKeyA to snapshot),
        )

        application {
            installSapphireAgentApi(snapshotUseCase)
        }

        val foundResponse = client.get("/api/v1/devices/$deviceKeyA/snapshots/latest")
        val missingResponse = client.get("/api/v1/devices/missing/snapshots/latest")

        assertEquals(HttpStatusCode.OK, foundResponse.status)
        assertEquals(
            snapshot.timestamp,
            TestJson.decodeFromString<LatestDeviceSuccessResponse>(foundResponse.bodyAsText()).payload.timestamp,
        )
        assertEquals(HttpStatusCode.NotFound, missingResponse.status)
        assertEquals(
            "snapshot_not_found",
            TestJson.decodeFromString<FailureResponse>(missingResponse.bodyAsText()).error.code,
        )
        assertEquals(listOf(deviceKeyA, "missing"), snapshotUseCase.latestDeviceRequests)
    }

    @Test
    fun `history endpoint returns selected node device snapshots`() = testApplication {
        val nodeId = testNodeId(1)
        val deviceKey = deviceKeyA
        val snapshotUseCase = FakeSnapshotUseCase(
            historyPayload = RawNodeDeviceHistory(
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
            installSapphireAgentApi(snapshotUseCase)
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
            snapshotUseCase.historyRequests,
        )
    }

    @Test
    fun `history endpoint returns empty snapshots for unknown node device`() = testApplication {
        val nodeId = testNodeId(1)
        val snapshotUseCase = FakeSnapshotUseCase(
            historyPayload = RawNodeDeviceHistory(
                nodeId = nodeId.toString(),
                nodeName = "",
                deviceKey = "missing",
                snapshots = emptyList(),
            ),
        )

        application {
            installSapphireAgentApi(snapshotUseCase)
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
        val snapshotUseCase = FakeSnapshotUseCase()

        application {
            installSapphireAgentApi(snapshotUseCase)
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
        assertEquals(emptyList(), snapshotUseCase.historyRequests)
    }
}

@Serializable
private data class LatestSnapshotsSuccessResponse(
    val payload: LatestSnapshotsPayload,
)

@Serializable
private data class LatestDeviceSuccessResponse(
    val payload: EvaluatedDiskSnapshot,
)

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

private class FakeSnapshotUseCase(
    private val latestNodes: List<RawNodeSnapshot> = emptyList(),
    private val latestPage: LatestSnapshotPage = LatestSnapshotPage(emptyList(), hasMore = false, nextCursor = null),
    private val latestByDeviceKey: Map<String, DiskSnapshot> = emptyMap(),
    private val historyPayload: RawNodeDeviceHistory = RawNodeDeviceHistory(
        nodeId = "",
        nodeName = "",
        deviceKey = "",
        snapshots = emptyList(),
    ),
) : SnapshotUseCase {
    val historyRequests = mutableListOf<HistoryRequest>()
    val latestDeviceRequests = mutableListOf<String>()
    val latestPageRequests = mutableListOf<LatestSnapshotPageRequest>()

    override suspend fun saveSnapshot(snapshot: DiskSnapshot) = Unit

    override suspend fun findLatestNodes(): List<RawNodeSnapshot> = latestNodes

    override suspend fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage {
        latestPageRequests += request
        return latestPage
    }

    override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? {
        latestDeviceRequests += deviceKey
        return latestByDeviceKey[deviceKey]
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory {
        historyRequests += HistoryRequest(
            nodeId = nodeId.toString(),
            deviceKey = deviceKey,
            query = query,
        )
        return historyPayload
    }
}
