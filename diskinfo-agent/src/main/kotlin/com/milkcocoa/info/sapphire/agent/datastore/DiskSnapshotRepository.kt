package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DiskSnapshotRepository {
    fun findLatestForEachDevice(): List<DiskSnapshot> = transaction {
        DiskSnapshotTable
            .selectAll()
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .map { it[DiskSnapshotTable.snapshotJson] }
            .distinctBy { it.deviceKey }
    }

    fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = transaction {
        DiskSnapshotTable
            .selectAll()
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .map { it[DiskSnapshotTable.snapshotJson] }
            .firstOrNull { it.deviceKey == deviceKey }
    }
}
