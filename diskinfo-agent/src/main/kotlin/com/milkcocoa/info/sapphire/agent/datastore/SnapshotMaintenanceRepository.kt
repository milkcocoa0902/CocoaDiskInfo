package com.milkcocoa.info.sapphire.agent.datastore

import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime

/**
 * Storage boundary for raw snapshot retention maintenance.
 *
 * The cutoff is compared with `collect_time` (the observation time), not ingestion time. Both
 * operations are intended to run in the caller's transaction: count is suitable for dry-run
 * reporting, while delete performs the hard delete permitted by the raw-snapshot policy.
 */
interface SnapshotMaintenanceRepository {
    /** Counts observations strictly older than [cutoff] without changing storage. */
    fun countSnapshotsBefore(cutoff: OffsetDateTime): Long

    /** Deletes observations strictly older than [cutoff] and returns the affected row count. */
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
