package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotCursor
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.api.PageMetadata
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StandaloneLatestSnapshotsQueryService(
    private val snapshotUseCase: SnapshotUseCase,
    private val clock: Clock = Clock.systemUTC(),
) : LatestSnapshotsQueryService {
    override suspend fun findLatestPage(request: LatestSnapshotPageRequest): HubLatestSnapshotsQueryResult {
        val rows = snapshotUseCase.findLatestNodes()
            .flatMap { node -> node.devices.map { snapshot -> Row(node, snapshot) } }
            .sortedWith(compareBy<Row>({ it.node.nodeId.toUuid() }, { it.snapshot.deviceKey }))
            .filter { row -> request.cursor == null || row.isAfter(request.cursor) }
        val selected = rows.take(request.limit)
        val hasMore = rows.size > selected.size
        val grouped = selected.groupBy { it.node.nodeId }.map { (_, nodeRows) ->
            val source = nodeRows.first().node
            NodeSnapshot(
                nodeId = source.nodeId,
                nodeName = source.nodeName,
                devices = nodeRows.map { it.snapshot },
                status = NodeStatus.ACTIVE,
            )
        }
        val next = selected.lastOrNull()?.takeIf { hasMore }?.let {
            LatestSnapshotCursor(it.node.nodeId.toUuid(), it.snapshot.deviceKey)
        }
        return HubLatestSnapshotsQueryResult(
            payload = LatestSnapshotsPayload(
                nodes = grouped,
                generatedAt = clock.instant().toKotlinInstant(),
                pagination = PageMetadata(limit = request.limit, hasMore = hasMore),
            ),
            nextCursor = next,
        )
    }

    override suspend fun findNodeLatest(nodeId: Uuid, deviceKey: String): HubNodeLatestQueryResult? {
        val stored = snapshotUseCase.findLatest(nodeId, deviceKey) ?: return null
        return HubNodeLatestQueryResult(
            snapshot = stored.snapshot,
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
            payload = history.copy(nodeName = nodeName),
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

    private fun Row.isAfter(cursor: LatestSnapshotCursor): Boolean {
        val nodeId = node.nodeId.toUuid()
        return nodeId > cursor.nodeId || (nodeId == cursor.nodeId && snapshot.deviceKey > cursor.deviceKey)
    }

    private fun String.toUuid(): Uuid {
        val value = UUID.fromString(this)
        return Uuid.fromLongs(value.mostSignificantBits, value.leastSignificantBits)
    }

    private fun Instant.toKotlinInstant(): KotlinInstant = KotlinInstant.fromEpochMilliseconds(toEpochMilli())

    private data class Row(
        val node: NodeSnapshot,
        val snapshot: com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot,
    )
}
