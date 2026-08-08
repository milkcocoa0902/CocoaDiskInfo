package com.milkcocoa.info.sapphire.agent.datastore

import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime

interface SnapshotMaintenanceRepository {
    fun countSnapshotsBefore(cutoff: OffsetDateTime): Long

    fun deleteSnapshotsBefore(cutoff: OffsetDateTime): Long
}

class ExposedSnapshotMaintenanceRepository : SnapshotMaintenanceRepository {
    override fun countSnapshotsBefore(cutoff: OffsetDateTime): Long =
        DiskSnapshotTable
            .selectAll()
            .where { DiskSnapshotTable.collectTimeStamp less cutoff }
            .count()

    override fun deleteSnapshotsBefore(cutoff: OffsetDateTime): Long =
        DiskSnapshotTable
            .deleteWhere { DiskSnapshotTable.collectTimeStamp less cutoff }
            .toLong()
}
