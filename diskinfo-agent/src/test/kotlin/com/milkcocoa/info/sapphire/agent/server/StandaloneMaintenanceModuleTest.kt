package com.milkcocoa.info.sapphire.agent.server

import com.milkcocoa.info.sapphire.agent.maintenance.StandaloneMaintenanceRunner
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupRequest
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumResult
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotCleanupVacuumSkippedReason
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotMaintenanceUseCase
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StandaloneMaintenanceModuleTest {
    @Test
    fun `periodic cleanup runs across multiple intervals`() = runBlocking {
        val useCase = CountingMaintenanceUseCase()
        val runner = StandaloneMaintenanceRunner(
            maintenanceUseCase = useCase,
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )
        val application = TestApplication {
            application {
                installStandaloneMaintenance(runner, 5.milliseconds)
            }
        }

        try {
            application.start()
            withTimeout(2.seconds) { useCase.calledAtLeastTwice.await() }

            assertTrue(useCase.callCount.get() >= 2)
        } finally {
            application.stop()
        }
    }

    @Test
    fun `application lifecycle starts and cancels periodic cleanup`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val runner = StandaloneMaintenanceRunner(
            maintenanceUseCase = CancellingMaintenanceUseCase(started, cancelled),
            rawSnapshotDays = 30,
            vacuumAfterCleanup = false,
        )
        val application = TestApplication {
            application {
                installStandaloneMaintenance(runner, 1.milliseconds)
            }
        }

        application.start()
        withTimeout(2.seconds) { started.await() }
        application.stop()

        withTimeout(2.seconds) { cancelled.await() }
        assertTrue(started.isCompleted)
        assertTrue(cancelled.isCompleted)
    }
}

private class CountingMaintenanceUseCase : SnapshotMaintenanceUseCase {
    val callCount = AtomicInteger()
    val calledAtLeastTwice = CompletableDeferred<Unit>()

    override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
        if (callCount.incrementAndGet() >= 2) {
            calledAtLeastTwice.complete(Unit)
        }
        return successfulCleanupResult()
    }
}

private class CancellingMaintenanceUseCase(
    private val started: CompletableDeferred<Unit>,
    private val cancelled: CompletableDeferred<Unit>,
) : SnapshotMaintenanceUseCase {
    override suspend fun cleanup(request: SnapshotCleanupRequest): SnapshotCleanupResult {
        started.complete(Unit)
        try {
            awaitCancellation()
        } finally {
            cancelled.complete(Unit)
        }
    }
}

private fun successfulCleanupResult() = SnapshotCleanupResult(
    cutoff = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
    tables = emptyList(),
    dryRun = false,
    vacuum = SnapshotCleanupVacuumResult(
        requested = false,
        executed = false,
        skippedReason = SnapshotCleanupVacuumSkippedReason.NOT_REQUESTED,
    ),
)
