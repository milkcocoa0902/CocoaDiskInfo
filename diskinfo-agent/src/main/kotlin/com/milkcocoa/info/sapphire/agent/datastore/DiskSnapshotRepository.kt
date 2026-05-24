package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class DiskSnapshotRepository {
    fun findLatestForEachDevice(): List<DiskSnapshot> = transaction {
        DiskSnapshotTable
            .selectAll()
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .filter { it[DiskSnapshotTable.deviceKey] != null }
            .distinctBy { it[DiskSnapshotTable.deviceKey] }
            .map { it[DiskSnapshotTable.snapshotJson] }
    }

    fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = transaction {
        DiskSnapshotTable
            .selectAll()
            .where { DiskSnapshotTable.deviceKey eq deviceKey }
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DiskSnapshotTable.snapshotJson)
    }
}
