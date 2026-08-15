package com.milkcocoa.info.sapphire.agent.datastore

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.leftJoin
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class NodeAgentStatus {
    ACTIVE,
    DISABLED,
}

@OptIn(ExperimentalUuidApi::class)
data class NodeAgentRegistration(
    val nodeId: Uuid,
    val nodeName: String,
    val expectedCollectionIntervalSeconds: Long,
    val joinedAt: Instant,
) {
    init {
        require(nodeName.isNotBlank()) { "nodeName must not be blank." }
        require(nodeName.length <= 255) { "nodeName must be at most 255 characters." }
        require(expectedCollectionIntervalSeconds > 0) {
            "expectedCollectionIntervalSeconds must be greater than zero."
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
data class NodeAgentRegistryEntry(
    val nodeId: Uuid,
    val nodeName: String,
    val status: NodeAgentStatus,
    val expectedCollectionIntervalSeconds: Long,
    val joinedAt: Instant,
    val lastSeenAt: Instant?,
    val lastSnapshotReceivedAt: Instant?,
    val lastErrorCode: String?,
    val lastErrorMessage: String?,
    val lastFailureAt: Instant?,
)

data class BoundedNodeAgentRegistryEntries(
    val entries: List<NodeAgentRegistryEntry>,
    val hasMore: Boolean,
)

data class NodeAgentFailure(
    val code: String,
    val message: String,
    val failedAt: Instant,
) {
    init {
        require(code.isNotBlank()) { "failure code must not be blank." }
        require(code.length <= 128) { "failure code must be at most 128 characters." }
        require(message.length <= 1_024) { "failure message must be at most 1024 characters." }
    }
}

@OptIn(ExperimentalUuidApi::class)
interface NodeAgentRegistryRepository {
    fun activate(registration: NodeAgentRegistration): NodeAgentRegistryEntry

    fun recordHeartbeat(
        nodeId: Uuid,
        expectedCollectionIntervalSeconds: Long,
        seenAt: Instant,
    ): Boolean

    fun recordStoredSnapshot(nodeId: Uuid, receivedAt: Instant): Boolean

    fun recordDuplicate(nodeId: Uuid, seenAt: Instant): Boolean

    fun recordFailure(nodeId: Uuid, failure: NodeAgentFailure): Boolean

    fun disable(nodeId: Uuid): Boolean

    fun findById(nodeId: Uuid): NodeAgentRegistryEntry?

    fun findAll(): List<NodeAgentRegistryEntry>

    fun findActiveWithoutSnapshots(limit: Int): BoundedNodeAgentRegistryEntries
}

@OptIn(ExperimentalUuidApi::class)
object NodeAgentRegistryTable : Table("node_agent_registry") {
    val nodeId = uuid("node_id")
    val nodeName = varchar("node_name", 255)
    val status = varchar("status", 32)
    val expectedCollectionIntervalSeconds = long("expected_collection_interval_seconds")
    val joinedAt = timestampWithTimeZone("joined_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val lastSnapshotReceivedAt = timestampWithTimeZone("last_snapshot_received_at").nullable()
    val lastErrorCode = varchar("last_error_code", 128).nullable()
    val lastErrorMessage = varchar("last_error_message", 1_024).nullable()
    val lastFailureAt = timestampWithTimeZone("last_failure_at").nullable()

    override val primaryKey = PrimaryKey(nodeId)

    init {
        index(false, status, nodeId)
    }
}

@OptIn(ExperimentalUuidApi::class)
class ExposedNodeAgentRegistryRepository : NodeAgentRegistryRepository {
    override fun activate(registration: NodeAgentRegistration): NodeAgentRegistryEntry {
        val inserted = NodeAgentRegistryTable.insertIgnore {
            it[nodeId] = registration.nodeId
            it[nodeName] = registration.nodeName
            it[status] = NodeAgentStatus.ACTIVE.name
            it[expectedCollectionIntervalSeconds] = registration.expectedCollectionIntervalSeconds
            it[joinedAt] = registration.joinedAt.asOffsetDateTime()
        }.insertedCount

        if (inserted == 0) {
            NodeAgentRegistryTable.update({
                NodeAgentRegistryTable.nodeId eq registration.nodeId
            }) {
                it[nodeName] = registration.nodeName
                it[status] = NodeAgentStatus.ACTIVE.name
                it[expectedCollectionIntervalSeconds] = registration.expectedCollectionIntervalSeconds
                it[lastErrorCode] = null
                it[lastErrorMessage] = null
            }
        }

        return checkNotNull(findById(registration.nodeId)) {
            "Failed to activate node registry entry ${registration.nodeId}."
        }
    }

    override fun recordHeartbeat(
        nodeId: Uuid,
        expectedCollectionIntervalSeconds: Long,
        seenAt: Instant,
    ): Boolean {
        require(expectedCollectionIntervalSeconds > 0) {
            "expectedCollectionIntervalSeconds must be greater than zero."
        }
        val active = updateActive(nodeId) {
            it[NodeAgentRegistryTable.expectedCollectionIntervalSeconds] =
                expectedCollectionIntervalSeconds
            it[NodeAgentRegistryTable.lastErrorCode] = null
            it[NodeAgentRegistryTable.lastErrorMessage] = null
        }
        if (!active) return false

        updateLastSeen(nodeId, seenAt)
        return true
    }

    override fun recordStoredSnapshot(nodeId: Uuid, receivedAt: Instant): Boolean {
        val active = clearCurrentError(nodeId)
        if (!active) return false

        updateLastSeen(nodeId, receivedAt)
        NodeAgentRegistryTable.update({
            (NodeAgentRegistryTable.nodeId eq nodeId) and
                (NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name) and
                (NodeAgentRegistryTable.lastSnapshotReceivedAt.isNull() or
                    (NodeAgentRegistryTable.lastSnapshotReceivedAt less receivedAt.asOffsetDateTime()))
        }) {
            it[lastSnapshotReceivedAt] = receivedAt.asOffsetDateTime()
        }
        return true
    }

    override fun recordDuplicate(nodeId: Uuid, seenAt: Instant): Boolean {
        val active = clearCurrentError(nodeId)
        if (!active) return false

        updateLastSeen(nodeId, seenAt)
        return true
    }

    override fun recordFailure(nodeId: Uuid, failure: NodeAgentFailure): Boolean {
        val failedAt = failure.failedAt.asOffsetDateTime()
        return NodeAgentRegistryTable.update({
            (NodeAgentRegistryTable.nodeId eq nodeId) and
                (NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name) and
                (NodeAgentRegistryTable.lastFailureAt.isNull() or
                    (NodeAgentRegistryTable.lastFailureAt less failedAt))
        }) {
            it[lastErrorCode] = failure.code
            it[lastErrorMessage] = failure.message
            it[lastFailureAt] = failedAt
        } > 0
    }

    override fun disable(nodeId: Uuid): Boolean =
        NodeAgentRegistryTable.update({ NodeAgentRegistryTable.nodeId eq nodeId }) {
            it[status] = NodeAgentStatus.DISABLED.name
        } > 0

    override fun findById(nodeId: Uuid): NodeAgentRegistryEntry? =
        NodeAgentRegistryTable
            .selectAll()
            .where { NodeAgentRegistryTable.nodeId eq nodeId }
            .limit(1)
            .singleOrNull()
            ?.toRegistryEntry()

    override fun findAll(): List<NodeAgentRegistryEntry> =
        NodeAgentRegistryTable
            .selectAll()
            .orderBy(
                NodeAgentRegistryTable.nodeName to SortOrder.ASC,
                NodeAgentRegistryTable.nodeId to SortOrder.ASC,
            )
            .map { it.toRegistryEntry() }

    override fun findActiveWithoutSnapshots(limit: Int): BoundedNodeAgentRegistryEntries {
        require(limit in 1 until Int.MAX_VALUE) {
            "limit must be between 1 and ${Int.MAX_VALUE - 1}."
        }
        val selected = NodeAgentRegistryTable
            .leftJoin(
                otherTable = DiskSnapshotTable,
                onColumn = { NodeAgentRegistryTable.nodeId },
                otherColumn = { DiskSnapshotTable.nodeId },
            )
            .select(NodeAgentRegistryTable.columns)
            .where {
                (NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name) and
                    DiskSnapshotTable.nodeId.isNull()
            }
            .orderBy(NodeAgentRegistryTable.nodeId to SortOrder.ASC)
            .limit(limit + 1)
            .toList()

        return BoundedNodeAgentRegistryEntries(
            entries = selected.take(limit).map { it.toRegistryEntry() },
            hasMore = selected.size > limit,
        )
    }

    private fun updateLastSeen(nodeId: Uuid, seenAt: Instant) {
        val timestamp = seenAt.asOffsetDateTime()
        NodeAgentRegistryTable.update({
            (NodeAgentRegistryTable.nodeId eq nodeId) and
                (NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name) and
                (NodeAgentRegistryTable.lastSeenAt.isNull() or
                    (NodeAgentRegistryTable.lastSeenAt less timestamp))
        }) {
            it[lastSeenAt] = timestamp
        }
    }

    private fun clearCurrentError(nodeId: Uuid): Boolean = updateActive(nodeId) {
        it[NodeAgentRegistryTable.lastErrorCode] = null
        it[NodeAgentRegistryTable.lastErrorMessage] = null
    }

    private fun updateActive(
        nodeId: Uuid,
        body: NodeAgentRegistryTable.(org.jetbrains.exposed.v1.core.statements.UpdateStatement) -> Unit,
    ): Boolean = NodeAgentRegistryTable.update({
        (NodeAgentRegistryTable.nodeId eq nodeId) and
            (NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name)
    }, body = body) > 0

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRegistryEntry(): NodeAgentRegistryEntry =
        NodeAgentRegistryEntry(
            nodeId = this[NodeAgentRegistryTable.nodeId],
            nodeName = this[NodeAgentRegistryTable.nodeName],
            status = NodeAgentStatus.valueOf(this[NodeAgentRegistryTable.status]),
            expectedCollectionIntervalSeconds =
                this[NodeAgentRegistryTable.expectedCollectionIntervalSeconds],
            joinedAt = this[NodeAgentRegistryTable.joinedAt].toInstant(),
            lastSeenAt = this[NodeAgentRegistryTable.lastSeenAt]?.toInstant(),
            lastSnapshotReceivedAt = this[NodeAgentRegistryTable.lastSnapshotReceivedAt]?.toInstant(),
            lastErrorCode = this[NodeAgentRegistryTable.lastErrorCode],
            lastErrorMessage = this[NodeAgentRegistryTable.lastErrorMessage],
            lastFailureAt = this[NodeAgentRegistryTable.lastFailureAt]?.toInstant(),
        )

    private fun Instant.asOffsetDateTime(): OffsetDateTime =
        OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
