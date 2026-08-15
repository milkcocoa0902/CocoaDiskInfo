package com.milkcocoa.info.sapphire.agent.maintenance

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

sealed interface PeriodicMaintenanceRunResult {
    data class Completed(val cleanup: SnapshotCleanupResult) : PeriodicMaintenanceRunResult

    data class Failed(val error: Exception) : PeriodicMaintenanceRunResult

    data object Skipped : PeriodicMaintenanceRunResult
}

class PeriodicMaintenanceRunner(
    private val maintenanceUseCase: SnapshotMaintenanceUseCase,
    private val rawSnapshotDays: Int,
    private val vacuumAfterCleanup: Boolean,
) {
    private val executionMutex = Mutex()

    suspend fun runCleanup(): PeriodicMaintenanceRunResult {
        if (!executionMutex.tryLock()) {
            Colotok.warn(
                msg = "Skipped raw snapshot cleanup because a previous cleanup is still running.",
                attr = mapOf("raw_snapshot_days" to rawSnapshotDays.toString()),
            )
            return PeriodicMaintenanceRunResult.Skipped
        }

        return try {
            Colotok.info(
                msg = "Starting raw snapshot cleanup.",
                attr = mapOf(
                    "raw_snapshot_days" to rawSnapshotDays.toString(),
                    "dry_run" to "false",
                    "vacuum_requested" to vacuumAfterCleanup.toString(),
                ),
            )
            val result = maintenanceUseCase.cleanup(
                SnapshotCleanupRequest(
                    rawSnapshotDays = rawSnapshotDays,
                    dryRun = false,
                    vacuumAfterCleanup = vacuumAfterCleanup,
                ),
            )
            Colotok.info(
                msg = "Completed raw snapshot cleanup.",
                attr = mapOf(
                    "cutoff" to result.cutoff.toString(),
                    "matched_rows" to result.matchedRowCount.toString(),
                    "deleted_rows" to result.deletedRowCount.toString(),
                    "vacuum_executed" to result.vacuum.executed.toString(),
                ),
            )
            PeriodicMaintenanceRunResult.Completed(result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Colotok.warn(
                msg = "Failed to clean up raw snapshots; long-running execution will continue.",
                attr = mapOf(
                    "raw_snapshot_days" to rawSnapshotDays.toString(),
                    "error" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
            PeriodicMaintenanceRunResult.Failed(error)
        } finally {
            executionMutex.unlock()
        }
    }
}

sealed interface StandaloneMaintenanceRunResult {
    data class Completed(val cleanup: SnapshotCleanupResult) : StandaloneMaintenanceRunResult

    data class Failed(val error: Exception) : StandaloneMaintenanceRunResult

    data object Skipped : StandaloneMaintenanceRunResult
}

class StandaloneMaintenanceRunner(
    maintenanceUseCase: SnapshotMaintenanceUseCase,
    rawSnapshotDays: Int,
    vacuumAfterCleanup: Boolean,
) {
    private val delegate = PeriodicMaintenanceRunner(
        maintenanceUseCase = maintenanceUseCase,
        rawSnapshotDays = rawSnapshotDays,
        vacuumAfterCleanup = vacuumAfterCleanup,
    )

    suspend fun runCleanup(): StandaloneMaintenanceRunResult = when (val result = delegate.runCleanup()) {
        is PeriodicMaintenanceRunResult.Completed ->
            StandaloneMaintenanceRunResult.Completed(result.cleanup)

        is PeriodicMaintenanceRunResult.Failed ->
            StandaloneMaintenanceRunResult.Failed(result.error)

        PeriodicMaintenanceRunResult.Skipped -> StandaloneMaintenanceRunResult.Skipped
    }
}
