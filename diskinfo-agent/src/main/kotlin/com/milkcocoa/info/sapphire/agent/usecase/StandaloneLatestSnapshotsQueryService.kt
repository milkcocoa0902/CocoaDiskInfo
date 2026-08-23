package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.api.PageMetadata
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.health.DefaultHealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import com.milkcocoa.info.sapphire.core.snapshot.toEvaluatedDiskSnapshot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StandaloneLatestSnapshotsQueryService(
    private val snapshotUseCase: SnapshotUseCase,
    private val clock: Clock = Clock.systemUTC(),
    private val healthPolicy: HealthPolicy = DefaultHealthPolicy,
) : LatestSnapshotsQueryService {
    override suspend fun findLatestPage(request: LatestSnapshotPageRequest): HubLatestSnapshotsQueryResult {
        val page = snapshotUseCase.findLatestPage(request)
        val grouped = page.rows.groupBy { it.origin.nodeId }.map { (_, nodeRows) ->
            val source = nodeRows.first().origin
            NodeSnapshot(
                nodeId = source.nodeId.toString(),
                nodeName = source.nodeName,
                devices = nodeRows.map { it.snapshot.toEvaluatedDiskSnapshot(healthPolicy) },
                status = NodeStatus.ACTIVE,
            )
        }
        return HubLatestSnapshotsQueryResult(
            payload = LatestSnapshotsPayload(
                nodes = grouped,
                generatedAt = clock.instant().toKotlinInstant(),
                pagination = PageMetadata(limit = request.limit, hasMore = page.hasMore),
                evaluationPolicy = healthPolicy.metadata,
            ),
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun findNodeLatest(nodeId: Uuid, deviceKey: String): HubNodeLatestQueryResult? {
        val stored = snapshotUseCase.findLatest(nodeId, deviceKey) ?: return null
        return HubNodeLatestQueryResult(
            snapshot = stored.snapshot.toEvaluatedDiskSnapshot(healthPolicy),
            freshness = NodeScopedFreshnessContext(
                nodeId = nodeId.toString(),
                nodeName = stored.origin.nodeName,
                status = NodeStatus.ACTIVE,
                lastSeenAt = null,
                deviceState = stored.toDeviceState(clock.instant()),
                snapshotMissing = false,
                currentError = null,
            ),
        )
    }

    override suspend fun findNodeHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): HubNodeHistoryQueryResult? {
        val history = snapshotUseCase.findHistory(nodeId, deviceKey, query)
        val stored = snapshotUseCase.findLatest(nodeId, deviceKey)
        val nodeName = stored?.origin?.nodeName ?: history.nodeName
        return HubNodeHistoryQueryResult(
            payload = NodeDeviceHistoryPayload(
                nodeId = history.nodeId,
                nodeName = nodeName,
                deviceKey = history.deviceKey,
                snapshots = history.snapshots.map { it.toEvaluatedDiskSnapshot(healthPolicy) },
                evaluationPolicy = healthPolicy.metadata,
            ),
            freshness = NodeScopedFreshnessContext(
                nodeId = nodeId.toString(),
                nodeName = nodeName,
                status = NodeStatus.ACTIVE,
                lastSeenAt = null,
                deviceState = stored?.toDeviceState(clock.instant()) ?: DeviceState(deviceKey),
                snapshotMissing = stored == null,
                currentError = null,
            ),
        )
    }

    private fun com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot.toDeviceState(now: Instant): DeviceState {
        val age = Duration.between(receivedAt, now).let { if (it.isNegative) Duration.ZERO else it }
        return DeviceState(
            deviceKey = snapshot.deviceKey,
            lastReceivedAt = receivedAt.toKotlinInstant(),
            ageMs = runCatching(age::toMillis).getOrDefault(Long.MAX_VALUE),
            stale = false,
        )
    }

    private fun Instant.toKotlinInstant(): KotlinInstant = KotlinInstant.fromEpochMilliseconds(toEpochMilli())
}
