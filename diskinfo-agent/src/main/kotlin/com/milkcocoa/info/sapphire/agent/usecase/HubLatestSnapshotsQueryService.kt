package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotCursor
import com.milkcocoa.info.sapphire.agent.datastore.LatestSnapshotPageRequest
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistryEntry
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentStatus
import com.milkcocoa.info.sapphire.agent.datastore.StoredDiskSnapshot
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.api.PageMetadata
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Instant as KotlinInstant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class HubLatestSnapshotsQueryResult(
    val payload: LatestSnapshotsPayload,
    val nextCursor: LatestSnapshotCursor?,
)

data class NodeScopedFreshnessContext(
    val nodeId: String,
    val nodeName: String,
    val status: NodeStatus,
    val lastSeenAt: KotlinInstant?,
    val deviceState: DeviceState,
    val snapshotMissing: Boolean,
    val currentError: NodeApiError?,
)

data class HubNodeLatestQueryResult(
    val snapshot: DiskSnapshot?,
    val freshness: NodeScopedFreshnessContext,
)

data class HubNodeHistoryQueryResult(
    val payload: NodeDeviceHistoryPayload,
    val freshness: NodeScopedFreshnessContext,
)

@OptIn(ExperimentalUuidApi::class)
interface LatestSnapshotsQueryService {
    suspend fun findLatestPage(request: LatestSnapshotPageRequest): HubLatestSnapshotsQueryResult
    suspend fun findNodeLatest(nodeId: Uuid, deviceKey: String): HubNodeLatestQueryResult?
    suspend fun findNodeHistory(nodeId: Uuid, deviceKey: String, query: HistoryQuery): HubNodeHistoryQueryResult?
}

@OptIn(ExperimentalUuidApi::class)
class HubLatestSnapshotsQueryService(
    private val snapshotRepository: DiskSnapshotRepository,
    private val registryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock = Clock.systemUTC(),
    private val errorLimit: Int = DEFAULT_ERROR_LIMIT,
) : LatestSnapshotsQueryService {
    init {
        require(errorLimit in 1..MAX_ERROR_LIMIT) {
            "errorLimit must be between 1 and $MAX_ERROR_LIMIT."
        }
    }

    override suspend fun findLatestPage(
        request: LatestSnapshotPageRequest,
    ): HubLatestSnapshotsQueryResult {
        val now = clock.instant()
        val (page, registryEntries, missingNodes) = transactionRunner.readOnly {
            Triple(
                snapshotRepository.findLatestPage(request),
                registryRepository.findAll(),
                registryRepository.findActiveWithoutSnapshots(errorLimit),
            )
        }
        val activeRegistry = registryEntries
            .filter { it.status == NodeAgentStatus.ACTIVE }
            .associateBy(NodeAgentRegistryEntry::nodeId)
        val pageRows = page.rows.filter { it.origin.nodeId in activeRegistry }
        val nodes = pageRows
            .groupBy { it.origin.nodeId }
            .mapNotNull { (nodeId, rows) ->
                val registry = activeRegistry[nodeId] ?: return@mapNotNull null
                NodeSnapshot(
                    nodeId = nodeId.toString(),
                    nodeName = registry.nodeName,
                    devices = rows.map(StoredDiskSnapshot::snapshot),
                    status = NodeStatus.ACTIVE,
                    lastSeenAt = registry.lastSeenAt?.toKotlinInstant(),
                    deviceStates = rows.map { row ->
                        row.toDeviceState(registry, now)
                    },
                )
            }

        val errors = ArrayList<NodeApiError>(errorLimit)
        var partial = missingNodes.hasMore
        var errorsTruncated = missingNodes.hasMore
        fun recordError(error: NodeApiError) {
            partial = true
            if (errors.size < errorLimit) {
                errors += error
            } else {
                errorsTruncated = true
            }
        }
        activeRegistry.values
            .sortedBy { it.nodeId }
            .forEach { registry ->
                registry.currentApiError()?.let(::recordError)
            }
        missingNodes.entries.forEach { registry ->
            recordError(
                NodeApiError(
                    code = ERROR_SNAPSHOT_MISSING,
                    message = "Node has no stored snapshot.",
                    nodeId = registry.nodeId.toString(),
                ),
            )
        }
        pageRows.forEach { row ->
            val registry = activeRegistry.getValue(row.origin.nodeId)
            if (row.toDeviceState(registry, now).stale) {
                recordError(
                    NodeApiError(
                        code = ERROR_SNAPSHOT_STALE,
                        message = "Latest snapshot for device ${row.snapshot.deviceKey} is stale.",
                        nodeId = registry.nodeId.toString(),
                    ),
                )
            }
        }

        return HubLatestSnapshotsQueryResult(
            payload = LatestSnapshotsPayload(
                nodes = nodes,
                generatedAt = now.toKotlinInstant(),
                partial = partial,
                errors = errors,
                errorsTruncated = errorsTruncated,
                pagination = PageMetadata(
                    limit = request.limit,
                    // Cursor encoding belongs to the HTTP adapter; keep application output typed.
                    nextCursor = null,
                    hasMore = page.hasMore,
                ),
            ),
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun findNodeLatest(
        nodeId: Uuid,
        deviceKey: String,
    ): HubNodeLatestQueryResult? {
        val now = clock.instant()
        val (registry, latest) = transactionRunner.readOnly {
            registryRepository.findById(nodeId) to snapshotRepository.findLatest(nodeId, deviceKey)
        }
        registry ?: return null
        return HubNodeLatestQueryResult(
            snapshot = latest?.snapshot,
            freshness = freshnessContext(registry, deviceKey, latest, now),
        )
    }

    override suspend fun findNodeHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): HubNodeHistoryQueryResult? {
        val now = clock.instant()
        val (registry, latest, history) = transactionRunner.readOnly {
            Triple(
                registryRepository.findById(nodeId),
                snapshotRepository.findLatest(nodeId, deviceKey),
                snapshotRepository.findHistory(nodeId, deviceKey, query),
            )
        }
        registry ?: return null
        return HubNodeHistoryQueryResult(
            payload = history.copy(nodeName = registry.nodeName),
            freshness = freshnessContext(registry, deviceKey, latest, now),
        )
    }

    private fun freshnessContext(
        registry: NodeAgentRegistryEntry,
        deviceKey: String,
        latest: StoredDiskSnapshot?,
        now: Instant,
    ): NodeScopedFreshnessContext = NodeScopedFreshnessContext(
        nodeId = registry.nodeId.toString(),
        nodeName = registry.nodeName,
        status = registry.status.toApiStatus(),
        lastSeenAt = registry.lastSeenAt?.toKotlinInstant(),
        deviceState = latest?.toDeviceState(registry, now) ?: DeviceState(deviceKey = deviceKey),
        snapshotMissing = latest == null,
        currentError = registry.currentApiError(),
    )

    private fun StoredDiskSnapshot.toDeviceState(
        registry: NodeAgentRegistryEntry,
        now: Instant,
    ): DeviceState {
        val elapsed = Duration.between(receivedAt, now)
        val nonNegativeElapsed = if (elapsed.isNegative) Duration.ZERO else elapsed
        val thresholdSeconds = if (
            registry.expectedCollectionIntervalSeconds > Long.MAX_VALUE / 2
        ) {
            Long.MAX_VALUE
        } else {
            registry.expectedCollectionIntervalSeconds * 2
        }
        return DeviceState(
            deviceKey = snapshot.deviceKey,
            lastReceivedAt = receivedAt.toKotlinInstant(),
            ageMs = nonNegativeElapsed.toMillisSaturated(),
            stale = nonNegativeElapsed > Duration.ofSeconds(thresholdSeconds),
        )
    }

    private fun NodeAgentRegistryEntry.currentApiError(): NodeApiError? {
        if (lastErrorCode == null && lastErrorMessage == null) return null
        return NodeApiError(
            code = ERROR_CURRENT_NODE,
            message = "Node reports a current collection error.",
            nodeId = nodeId.toString(),
        )
    }

    private fun NodeAgentStatus.toApiStatus(): NodeStatus = when (this) {
        NodeAgentStatus.ACTIVE -> NodeStatus.ACTIVE
        NodeAgentStatus.DISABLED -> NodeStatus.DISABLED
    }

    private fun Instant.toKotlinInstant(): KotlinInstant =
        KotlinInstant.fromEpochMilliseconds(toEpochMilli())

    private fun Duration.toMillisSaturated(): Long = try {
        toMillis()
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    companion object {
        const val DEFAULT_ERROR_LIMIT = 100
        const val MAX_ERROR_LIMIT = 500
        const val ERROR_SNAPSHOT_MISSING = "node_snapshot_missing"
        const val ERROR_SNAPSHOT_STALE = "node_snapshot_stale"
        const val ERROR_CURRENT_NODE = "node_current_error"
    }
}
