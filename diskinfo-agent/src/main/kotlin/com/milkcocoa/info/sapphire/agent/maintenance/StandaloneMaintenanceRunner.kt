package com.milkcocoa.info.sapphire.agent.maintenance

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Result of one serialized periodic raw-snapshot cleanup attempt. */
sealed interface PeriodicMaintenanceRunResult {
    /** Cleanup completed and includes dry-run/deletion/vacuum accounting from the use case. */
    data class Completed(val cleanup: SnapshotCleanupResult) : PeriodicMaintenanceRunResult

    /** Cleanup failed; the standalone loop may continue operating. */
    data class Failed(val error: Exception) : PeriodicMaintenanceRunResult

    /** Cleanup was skipped because another invocation still owns the execution mutex. */
    data object Skipped : PeriodicMaintenanceRunResult
}

/**
 * Serializes periodic cleanup and applies the raw-snapshot retention policy.
 *
 * The retention cutoff is delegated to [maintenanceUseCase]. A failed cleanup is reported rather
 * than terminating long-running standalone execution; coroutine cancellation is propagated. The
 * optional vacuum is a separate backend maintenance action after cleanup, not part of the delete
 * transaction.
 */
class PeriodicMaintenanceRunner(
    private val maintenanceUseCase: SnapshotMaintenanceUseCase,
    private val rawSnapshotDays: Int,
    private val vacuumAfterCleanup: Boolean,
) {
    private val executionMutex = Mutex()

    /** Runs one non-dry-run cleanup, or returns [PeriodicMaintenanceRunResult.Skipped]. */
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

/** Public standalone-mode projection of [PeriodicMaintenanceRunResult]. */
sealed interface StandaloneMaintenanceRunResult {
    /** Cleanup completed successfully. */
    data class Completed(val cleanup: SnapshotCleanupResult) : StandaloneMaintenanceRunResult

    /** Cleanup failed but the caller can keep the process alive. */
    data class Failed(val error: Exception) : StandaloneMaintenanceRunResult

    /** Cleanup did not start because another invocation was already running. */
    data object Skipped : StandaloneMaintenanceRunResult
}

/** Standalone-facing adapter that preserves the periodic cleanup and failure contract. */
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

    /** Runs one serialized cleanup attempt using the configured retention and vacuum settings. */
    suspend fun runCleanup(): StandaloneMaintenanceRunResult = when (val result = delegate.runCleanup()) {
        is PeriodicMaintenanceRunResult.Completed ->
            StandaloneMaintenanceRunResult.Completed(result.cleanup)

        is PeriodicMaintenanceRunResult.Failed ->
            StandaloneMaintenanceRunResult.Failed(result.error)

        PeriodicMaintenanceRunResult.Skipped -> StandaloneMaintenanceRunResult.Skipped
    }
}
