package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import com.milkcocoa.info.sapphire.agent.testSnapshotRecord
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ExposedNodeAgentRegistryRepositoryTest {
    @Test
    fun `activate creates independent nodes and reactivation keeps original join time`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedNodeAgentRegistryRepository()
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")
        val nodeA = testNodeId(1)
        val nodeB = testNodeId(2)

        transaction {
            repository.activate(NodeAgentRegistration(nodeA, "node-a", 60, joinedAt))
            repository.activate(NodeAgentRegistration(nodeB, "node-b", 120, joinedAt.plusSeconds(1)))
            repository.disable(nodeA)
            repository.activate(
                NodeAgentRegistration(nodeA, "renamed-node-a", 30, joinedAt.plusSeconds(100)),
            )
        }

        val entries = transaction { repository.findAll() }
        val reactivated = entries.single { it.nodeId == nodeA }
        assertEquals(2, entries.size)
        assertEquals("renamed-node-a", reactivated.nodeName)
        assertEquals(NodeAgentStatus.ACTIVE, reactivated.status)
        assertEquals(30, reactivated.expectedCollectionIntervalSeconds)
        assertEquals(joinedAt, reactivated.joinedAt)
    }

    @Test
    fun `heartbeat and ingest timestamps are monotonic and duplicate does not refresh snapshot`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedNodeAgentRegistryRepository()
        val nodeId = testNodeId(10)
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")
        val firstSnapshotAt = joinedAt.plusSeconds(60)

        transaction {
            repository.activate(NodeAgentRegistration(nodeId, "node-a", 60, joinedAt))
            assertTrue(repository.recordStoredSnapshot(nodeId, firstSnapshotAt))
            assertTrue(repository.recordHeartbeat(nodeId, 30, joinedAt.plusSeconds(120)))
            assertTrue(repository.recordHeartbeat(nodeId, 45, joinedAt.plusSeconds(90)))
            assertTrue(repository.recordDuplicate(nodeId, joinedAt.plusSeconds(180)))
        }

        val entry = transaction { repository.findById(nodeId) }
        assertEquals(45, entry?.expectedCollectionIntervalSeconds)
        assertEquals(joinedAt.plusSeconds(180), entry?.lastSeenAt)
        assertEquals(firstSnapshotAt, entry?.lastSnapshotReceivedAt)
    }

    @Test
    fun `valid activity clears current error while retaining last failure time`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedNodeAgentRegistryRepository()
        val nodeId = testNodeId(20)
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")
        val failureAt = joinedAt.plusSeconds(10)

        transaction {
            repository.activate(NodeAgentRegistration(nodeId, "node-a", 60, joinedAt))
            assertTrue(
                repository.recordFailure(
                    nodeId,
                    NodeAgentFailure("smartctl_failed", "device unavailable", failureAt),
                ),
            )
            assertTrue(repository.recordDuplicate(nodeId, joinedAt.plusSeconds(20)))
        }

        val entry = transaction { repository.findById(nodeId) }
        assertNull(entry?.lastErrorCode)
        assertNull(entry?.lastErrorMessage)
        assertEquals(failureAt, entry?.lastFailureAt)
    }

    @Test
    fun `disabled node rejects heartbeat and ingest updates`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedNodeAgentRegistryRepository()
        val nodeId = testNodeId(30)
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")

        transaction {
            repository.activate(NodeAgentRegistration(nodeId, "node-a", 60, joinedAt))
            repository.disable(nodeId)
            assertEquals(false, repository.recordHeartbeat(nodeId, 30, joinedAt.plusSeconds(1)))
            assertEquals(false, repository.recordStoredSnapshot(nodeId, joinedAt.plusSeconds(2)))
            assertEquals(false, repository.recordDuplicate(nodeId, joinedAt.plusSeconds(3)))
        }

        val entry = transaction { repository.findById(nodeId) }
        assertEquals(NodeAgentStatus.DISABLED, entry?.status)
        assertNull(entry?.lastSeenAt)
        assertNull(entry?.lastSnapshotReceivedAt)
    }

    @Test
    fun `active nodes without retained snapshot are reported with a bounded query`() {
        connectDiskSnapshotTestDatabase()
        val repository = ExposedNodeAgentRegistryRepository()
        val snapshotRepository = ExposedDiskSnapshotRepository()
        val joinedAt = Instant.parse("2026-08-15T00:00:00Z")
        val retainedNode = testNodeId(40)
        val removedByRetentionNode = testNodeId(41)
        val neverStoredNode = testNodeId(42)
        val disabledNode = testNodeId(43)

        transaction {
            listOf(retainedNode, removedByRetentionNode, neverStoredNode, disabledNode)
                .forEachIndexed { index, nodeId ->
                    repository.activate(
                        NodeAgentRegistration(nodeId, "node-$index", 60, joinedAt),
                    )
                }
            repository.disable(disabledNode)
            snapshotRepository.insert(
                testSnapshotRecord(
                    snapshot = testDiskSnapshot("retained-device", timestampMillis = 1_000),
                    nodeId = retainedNode,
                ),
            )
            snapshotRepository.insert(
                testSnapshotRecord(
                    snapshot = testDiskSnapshot("expired-device", timestampMillis = 2_000),
                    nodeId = removedByRetentionNode,
                ),
            )
            repository.recordStoredSnapshot(removedByRetentionNode, joinedAt.plusSeconds(120))
        }

        transaction {
            DiskSnapshotTable.deleteAll()
            snapshotRepository.insert(
                testSnapshotRecord(
                    snapshot = testDiskSnapshot("retained-device", timestampMillis = 3_000),
                    nodeId = retainedNode,
                ),
            )
        }

        val first = transaction { repository.findActiveWithoutSnapshots(limit = 1) }
        val all = transaction { repository.findActiveWithoutSnapshots(limit = 10) }

        assertEquals(1, first.entries.size)
        assertTrue(first.hasMore)
        assertEquals(
            setOf(removedByRetentionNode, neverStoredNode),
            all.entries.map { it.nodeId }.toSet(),
        )
        assertEquals(false, all.hasMore)
        assertEquals(
            joinedAt.plusSeconds(120),
            transaction { repository.findById(removedByRetentionNode) }?.lastSnapshotReceivedAt,
        )
    }
}
