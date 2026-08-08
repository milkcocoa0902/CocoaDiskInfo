package com.milkcocoa.info.sapphire.agent.maintenance

import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupTableResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumSkippedReason
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StandaloneMaintenanceRunnerTest {
    @Test
    fun `cleanup uses automatic settings and returns completed result`() = runBlocking {
        val useCase = RecordingMaintenanceUseCase()
        val runner = StandaloneMaintenanceRunner(useCase, rawSnapshotDays = 14, vacuumAfterCleanup = true)

        val result = runner.runCleanup()

        assertIs<StandaloneMaintenanceRunResult.Completed>(result)
        assertEquals(
            listOf(SnapshotCleanupRequest(rawSnapshotDays = 14, dryRun = false, vacuumAfterCleanup = true)),
            useCase.requests,
        )
    }

    @Test
    fun `non cancellation failure is reported and cancellation propagates`() = runBlocking {
        val failure = IllegalStateException("database unavailable")
        val failedRunner = StandaloneMaintenanceRunner(
            maintenanceUseCase = FunctionalMaintenanceUseCase { throw failure },
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )
        val cancelledRunner = StandaloneMaintenanceRunner(
            maintenanceUseCase = FunctionalMaintenanceUseCase { throw CancellationException("stopping") },
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )

        val result = failedRunner.runCleanup()

        assertEquals(failure, assertIs<StandaloneMaintenanceRunResult.Failed>(result).error)
        assertFailsWith<CancellationException> { cancelledRunner.runCleanup() }
        Unit
    }

    @Test
    fun `overlapping cleanup is skipped`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runner = StandaloneMaintenanceRunner(
            maintenanceUseCase = FunctionalMaintenanceUseCase {
                started.complete(Unit)
                release.await()
                cleanupResult()
            },
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )

        val first = async { runner.runCleanup() }
        started.await()
        val second = runner.runCleanup()
        release.complete(Unit)

        assertEquals(StandaloneMaintenanceRunResult.Skipped, second)
        assertIs<StandaloneMaintenanceRunResult.Completed>(first.await())
        Unit
    }
}

private class RecordingMaintenanceUseCase : SnapshotMaintenanceUseCase {
    val requests = mutableListOf<SnapshotCleanupRequest>()

    override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
        requests += request
        return cleanupResult()
    }
}

private class FunctionalMaintenanceUseCase(
    private val cleanupBlock: suspend (SnapshotCleanupRequest) -> SnapshotCleanupResult,
) : SnapshotMaintenanceUseCase {
    override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult = cleanupBlock(request)
}

private fun cleanupResult() = SnapshotCleanupResult(
    cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    tables = listOf(
        SnapshotCleanupTableResult(
            tableName = "disk_snapshot",
            matchedRowCount = 2,
            deletedRowCount = 2,
        ),
    ),
    dryRun = false,
    vacuum = SnapshotCleanupVacuumResult(
        requested = false,
        executed = false,
        skippedReason = SnapshotCleanupVacuumSkippedReason.NOT_REQUESTED,
    ),
)
