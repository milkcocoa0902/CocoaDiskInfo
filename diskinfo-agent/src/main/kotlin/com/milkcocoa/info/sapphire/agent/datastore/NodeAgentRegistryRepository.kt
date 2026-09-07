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

/** Lifecycle state of a registered node; disabled entries remain queryable for history context. */
enum class NodeAgentStatus {
    ACTIVE,
    DISABLED,
}

@OptIn(ExperimentalUuidApi::class)
/** Registration input persisted as current node-agent inventory, not heartbeat history. */
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
/** Materialized current node-agent status and latest operational observations. */
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

/** A bounded registry result; [hasMore] indicates that more entries exist beyond the page. */
data class BoundedNodeAgentRegistryEntries(
    val entries: List<NodeAgentRegistryEntry>,
    val hasMore: Boolean,
)

/** Latest structured failure to retain on a node registry entry. */
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
/**
 * Repository for current node-agent registry state.
 *
 * Heartbeats and latest timestamps are monotonic updates: older observations do not move a
 * persisted clock backwards. The repository stores current status/error state, not an unbounded
 * heartbeat event stream; inactive retention is a separate maintenance policy.
 */
interface NodeAgentRegistryRepository {
    /** Activates or refreshes [registration], preserving the node's existing identity. */
    fun activate(registration: NodeAgentRegistration): NodeAgentRegistryEntry

    /** Records an active heartbeat; returns false when [nodeId] is absent or disabled. */
    fun recordHeartbeat(
        nodeId: Uuid,
        expectedCollectionIntervalSeconds: Long,
        seenAt: Instant,
    ): Boolean

    /** Records a successfully stored snapshot and clears the current error. */
    fun recordStoredSnapshot(nodeId: Uuid, receivedAt: Instant): Boolean

    /** Records an idempotent duplicate ingest as activity without changing history. */
    fun recordDuplicate(nodeId: Uuid, seenAt: Instant): Boolean

    /** Updates the latest failure only when [failure.failedAt] is newer. */
    fun recordFailure(nodeId: Uuid, failure: NodeAgentFailure): Boolean

    /** Disables a registry entry without deleting its current context. */
    fun disable(nodeId: Uuid): Boolean

    /** Returns one registry entry, including disabled entries, when present. */
    fun findById(nodeId: Uuid): NodeAgentRegistryEntry?

    /** Returns all entries in deterministic display-name/node-id order. */
    fun findAll(): List<NodeAgentRegistryEntry>

    /** Returns active agents with no snapshots, bounded by [limit] and marked when more exist. */
    fun findActiveWithoutSnapshots(limit: Int): BoundedNodeAgentRegistryEntries
}

@OptIn(ExperimentalUuidApi::class)
/** Exposed mapping for current node-agent registry state; schema is owned by Flyway migrations. */
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
/** Exposed implementation that updates only current node registry state. */
class ExposedNodeAgentRegistryRepository : NodeAgentRegistryRepository {
    /** Inserts a node once or reactivates/refreshes its existing registry row. */
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

    /** Applies a heartbeat only to active rows and monotonically advances last-seen time. */
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

    /** Records successful ingest activity and monotonically advances snapshot receipt time. */
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

    /** Treats a duplicate ingest as valid activity while leaving snapshot history unchanged. */
    override fun recordDuplicate(nodeId: Uuid, seenAt: Instant): Boolean {
        val active = clearCurrentError(nodeId)
        if (!active) return false

        updateLastSeen(nodeId, seenAt)
        return true
    }

    /** Stores only a newer failure, preserving the latest-error semantics of the registry. */
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

    /** Marks the node disabled without removing its inventory context. */
    override fun disable(nodeId: Uuid): Boolean =
        NodeAgentRegistryTable.update({ NodeAgentRegistryTable.nodeId eq nodeId }) {
            it[status] = NodeAgentStatus.DISABLED.name
        } > 0

    /** Reads one current registry row regardless of status. */
    override fun findById(nodeId: Uuid): NodeAgentRegistryEntry? =
        NodeAgentRegistryTable
            .selectAll()
            .where { NodeAgentRegistryTable.nodeId eq nodeId }
            .limit(1)
            .singleOrNull()
            ?.toRegistryEntry()

    /** Reads all current rows in deterministic display order. */
    override fun findAll(): List<NodeAgentRegistryEntry> =
        NodeAgentRegistryTable
            .selectAll()
            .orderBy(
                NodeAgentRegistryTable.nodeName to SortOrder.ASC,
                NodeAgentRegistryTable.nodeId to SortOrder.ASC,
            )
            .map { it.toRegistryEntry() }

    /** Finds active inventory entries lacking any raw snapshot, with one-row lookahead paging. */
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
