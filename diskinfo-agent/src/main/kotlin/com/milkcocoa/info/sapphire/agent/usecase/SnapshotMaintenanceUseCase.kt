package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.SnapshotMaintenanceRepository
import com.milkcocoa.info.sapphire.agent.datastore.StorageMaintenanceOperation
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class SnapshotCleanupRequest(
    val rawSnapshotDays: Int,
    val dryRun: Boolean,
    val vacuumAfterCleanup: Boolean,
) {
    init {
        require(rawSnapshotDays in 1..365) {
            "rawSnapshotDays must be between 1 and 365 days."
        }
    }
}

data class SnapshotCleanupResult(
    val cutoff: OffsetDateTime,
    val tables: List<SnapshotCleanupTableResult>,
    val dryRun: Boolean,
    val vacuum: SnapshotCleanupVacuumResult,
) {
    val matchedRowCount: Long
        get() = tables.sumOf { it.matchedRowCount }

    val deletedRowCount: Long
        get() = tables.sumOf { it.deletedRowCount }
}

data class SnapshotCleanupTableResult(
    val tableName: String,
    val matchedRowCount: Long,
    val deletedRowCount: Long,
)

data class SnapshotCleanupVacuumResult(
    val requested: Boolean,
    val executed: Boolean,
    val skippedReason: SnapshotCleanupVacuumSkippedReason?,
)

enum class SnapshotCleanupVacuumSkippedReason {
    NOT_REQUESTED,
    DRY_RUN,
}

interface SnapshotMaintenanceUseCase {
    suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult
}

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
