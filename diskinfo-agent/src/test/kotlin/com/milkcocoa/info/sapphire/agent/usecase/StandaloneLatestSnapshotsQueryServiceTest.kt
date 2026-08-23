package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPage
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeDeviceHistory
import com.milkcocoa.info.sapphire.agent.datastore.RawNodeSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicyResult
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StandaloneLatestSnapshotsQueryServiceTest {
    @Test
    fun `latest uses the bounded page query before evaluation`() = kotlinx.coroutines.runBlocking {
        val nodeId = testNodeId(501)
        val snapshot = testDiskSnapshot("device-a", timestampMillis = 1_000)
        val page = LatestSnapshotPage(
            rows = listOf(
                StoredDiskSnapshot(
                    snapshotId = testNodeId(502),
                    ingestId = testNodeId(503),
                    origin = SnapshotOrigin(nodeId, "node-a"),
                    snapshot = snapshot,
                    receivedAt = Instant.parse("2026-08-15T00:00:00Z"),
                ),
            ),
            hasMore = true,
            nextCursor = null,
        )
        val useCase = PageOnlySnapshotUseCase(page)
        val policy = CountingPolicy()

        val result = StandaloneLatestSnapshotsQueryService(useCase, healthPolicy = policy)
            .findLatestPage(LatestSnapshotPageRequest(limit = 1))

        assertEquals(listOf(LatestSnapshotPageRequest(limit = 1)), useCase.pageRequests)
        assertEquals(1, policy.calls)
        assertEquals(DiskHealth.BAD, result.payload.nodes.single().devices.single().health)
        assertEquals(policy.metadata, result.payload.evaluationPolicy)
        assertEquals(true, result.payload.pagination?.hasMore)
    }

    private class PageOnlySnapshotUseCase(
        private val page: LatestSnapshotPage,
    ) : SnapshotUseCase {
        val pageRequests = mutableListOf<LatestSnapshotPageRequest>()

        override suspend fun saveSnapshot(snapshot: DiskSnapshot) = Unit
        override suspend fun findLatestNodes(): List<RawNodeSnapshot> =
            error("Standalone latest must use findLatestPage, never findLatestNodes.")

        override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = null

        override suspend fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage {
            pageRequests += request
            return page
        }

        override suspend fun findHistory(
            nodeId: Uuid,
            deviceKey: String,
            query: HistoryQuery,
        ): RawNodeDeviceHistory = RawNodeDeviceHistory(nodeId.toString(), "", deviceKey, emptyList())
    }

    private class CountingPolicy : HealthPolicy("test", 1) {
        var calls = 0
            private set

        override fun evaluate(snapshot: DiskSnapshot): HealthPolicyResult {
            calls += 1
            return HealthPolicyResult(snapshot.health, DiskHealth.BAD, emptyList())
        }
    }
}
