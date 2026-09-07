package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.SnapshotMaintenanceRepository
import com.milkcocoa.info.sapphire.agent.datastore.StorageMaintenanceOperation
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Retention and storage-maintenance policy for one cleanup invocation. */
data class SnapshotCleanupRequest(
    /** Number of days of raw snapshots to retain; must be 1..365. */
    val rawSnapshotDays: Int,
    /** Count matches without deleting rows when true. */
    val dryRun: Boolean,
    /** Request database vacuum after a non-dry cleanup. */
    val vacuumAfterCleanup: Boolean,
) {
    init {
        require(rawSnapshotDays in 1..365) {
            "rawSnapshotDays must be between 1 and 365 days."
        }
    }
}

/** Counts, cutoff, and vacuum outcome returned by cleanup. */
data class SnapshotCleanupResult(
    /** UTC timestamp before which rows are eligible for cleanup. */
    val cutoff: OffsetDateTime,
    /** Per-table match/delete counts. */
    val tables: List<SnapshotCleanupTableResult>,
    /** Whether deletion was suppressed. */
    val dryRun: Boolean,
    /** Requested/executed vacuum status. */
    val vacuum: SnapshotCleanupVacuumResult,
) {
    /** Sum of rows eligible for deletion across returned tables. */
    val matchedRowCount: Long
        get() = tables.sumOf { it.matchedRowCount }

    /** Sum of rows actually deleted across returned tables. */
    val deletedRowCount: Long
        get() = tables.sumOf { it.deletedRowCount }
}

/** Cleanup counts for one storage table. */
data class SnapshotCleanupTableResult(
    /** Stable table name reported by the repository. */
    val tableName: String,
    /** Rows matching the cutoff. */
    val matchedRowCount: Long,
    /** Rows deleted; zero for dry runs. */
    val deletedRowCount: Long,
)

/** Outcome of an optional post-cleanup vacuum. */
data class SnapshotCleanupVacuumResult(
    /** Whether the caller requested vacuum. */
    val requested: Boolean,
    /** Whether vacuum actually ran. */
    val executed: Boolean,
    /** Why vacuum did not run, when it was skipped. */
    val skippedReason: SnapshotCleanupVacuumSkippedReason?,
)

/** Reason an otherwise optional vacuum was not executed. */
enum class SnapshotCleanupVacuumSkippedReason {
    /** Vacuum was not requested. */
    NOT_REQUESTED,
    /** Vacuum is suppressed during a dry run. */
    DRY_RUN,
}

/** Boundary for retention cleanup and optional storage vacuum. */
interface SnapshotMaintenanceUseCase {
    /**
     * Counts/deletes rows before a clock-derived cutoff and optionally vacuums storage.
     * Dry runs use a read-only transaction and never delete or vacuum; real cleanup performs
     * count and delete together in one read-write transaction before vacuuming.
     */
    suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult
}

/** Transactional implementation of retention cleanup. */
class TransactionalSnapshotMaintenanceUseCase(
    private val repository: SnapshotMaintenanceRepository,
    private val transactionRunner: TransactionRunner,
    private val storageMaintenance: StorageMaintenanceOperation,
    private val clock: Clock = Clock.systemUTC(),
) : SnapshotMaintenanceUseCase {
    override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
        val cutoff = OffsetDateTime.ofInstant(
            clock.instant().minus(request.rawSnapshotDays.toLong(), ChronoUnit.DAYS),
            ZoneOffset.UTC,
        )

        val (matchedRowCount, deletedRowCount) = if (request.dryRun) {
            transactionRunner.readOnly {
                repository.countSnapshotsBefore(cutoff) to 0L
            }
        } else {
            transactionRunner.readWrite {
                repository.countSnapshotsBefore(cutoff) to repository.deleteSnapshotsBefore(cutoff)
            }
        }

        val vacuum = when {
            !request.vacuumAfterCleanup -> SnapshotCleanupVacuumResult(
                requested = false,
                executed = false,
                skippedReason = SnapshotCleanupVacuumSkippedReason.NOT_REQUESTED,
            )

            request.dryRun -> SnapshotCleanupVacuumResult(
                requested = true,
                executed = false,
                skippedReason = SnapshotCleanupVacuumSkippedReason.DRY_RUN,
            )

            else -> {
                storageMaintenance.vacuum()
                SnapshotCleanupVacuumResult(
                    requested = true,
                    executed = true,
                    skippedReason = null,
                )
            }
        }

        return SnapshotCleanupResult(
            cutoff = cutoff,
            tables = listOf(
                SnapshotCleanupTableResult(
                    tableName = "disk_snapshot",
                    matchedRowCount = matchedRowCount,
                    deletedRowCount = deletedRowCount,
                ),
            ),
            dryRun = request.dryRun,
            vacuum = vacuum,
        )
    }
}
