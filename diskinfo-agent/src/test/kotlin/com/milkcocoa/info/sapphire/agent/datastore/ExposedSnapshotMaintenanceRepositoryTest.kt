package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.agent.connectDiskSnapshotTestDatabase
import com.milkcocoa.info.sapphire.agent.insertDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import com.milkcocoa.info.sapphire.agent.testNodeId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class ExposedSnapshotMaintenanceRepositoryTest {
    private val repository = ExposedSnapshotMaintenanceRepository()
    private val cutoff = OffsetDateTime.ofInstant(Instant.ofEpochMilli(2_000), ZoneOffset.UTC)

    @Test
    fun `count selects only snapshots strictly older than cutoff without deleting`() {
        connectDiskSnapshotTestDatabase()
        insertSnapshotsAroundCutoff()

        val matchedRows = transaction {
            repository.countSnapshotsBefore(cutoff)
        }

        assertEquals(1, matchedRows)
        assertEquals(3, transaction { DiskSnapshotTable.selectAll().count() })
    }

    @Test
    fun `delete removes only snapshots strictly older than cutoff`() {
        connectDiskSnapshotTestDatabase()
        insertSnapshotsAroundCutoff()

        val deletedRows = transaction {
            repository.deleteSnapshotsBefore(cutoff)
        }

        assertEquals(1, deletedRows)
        assertEquals(2, transaction { DiskSnapshotTable.selectAll().count() })
        assertEquals(0, transaction { repository.countSnapshotsBefore(cutoff) })
    }

    private fun insertSnapshotsAroundCutoff() {
        val nodeId = testNodeId(1)
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot("before", timestampMillis = 1_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot("at-cutoff", timestampMillis = 2_000))
        insertDiskSnapshot(nodeId, "node-a", testDiskSnapshot("after", timestampMillis = 3_000))
    }
}
