package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.SnapshotMaintenanceRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedDiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedSnapshotMaintenanceRepository
import com.milkcocoa.info.sapphire.agent.datastore.ExposedTransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.StorageConnectionFactory
import com.milkcocoa.info.sapphire.agent.datastore.StorageMaintenanceOperation
import com.milkcocoa.info.sapphire.agent.datastore.StorageSettings
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMaintenanceOperation
import com.milkcocoa.info.sapphire.agent.datastore.createStorageMigratorFactory
import com.milkcocoa.info.sapphire.agent.testDiskSnapshot
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnapshotMaintenanceUseCaseTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-08T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `dry run counts snapshots in read only transaction without deleting or vacuuming`() = runBlocking {
        val repository = RecordingSnapshotMaintenanceRepository(countResult = 3)
        val transactionRunner = RecordingMaintenanceTransactionRunner()
        val storageMaintenance = RecordingStorageMaintenanceOperation()
        val useCase = TransactionalSnapshotMaintenanceUseCase(
            repository = repository,
            transactionRunner = transactionRunner,
            storageMaintenance = storageMaintenance,
            clock = clock,
        )

        val result = useCase.cleanup(
            SnapshotCleanupRequest(
                rawSnapshotDays = 30,
                dryRun = true,
                vacuumAfterCleanup = true,
            ),
        )

        assertEquals(OffsetDateTime.parse("2026-07-09T12:00:00Z"), result.cutoff)
        assertEquals(3, result.matchedRowCount)
        assertEquals(0, result.deletedRowCount)
        assertEquals(true, result.dryRun)
        assertEquals(
            SnapshotCleanupVacuumResult(
                requested = true,
                executed = false,
                skippedReason = SnapshotCleanupVacuumSkippedReason.DRY_RUN,
            ),
            result.vacuum,
        )
        assertEquals(1, transactionRunner.readOnlyCalls)
        assertEquals(0, transactionRunner.readWriteCalls)
        assertEquals(0, repository.deleteCalls)
        assertEquals(0, storageMaintenance.vacuumCalls)
    }

    @Test
    fun `cleanup deletes snapshots in read write transaction then vacuums`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingSnapshotMaintenanceRepository(
            countResult = 4,
            deleteResult = 4,
            calls = calls,
        )
        val transactionRunner = RecordingMaintenanceTransactionRunner(calls)
        val storageMaintenance = RecordingStorageMaintenanceOperation(calls)
        val useCase = TransactionalSnapshotMaintenanceUseCase(
            repository = repository,
            transactionRunner = transactionRunner,
            storageMaintenance = storageMaintenance,
            clock = clock,
        )

        val result = useCase.cleanup(
            SnapshotCleanupRequest(
                rawSnapshotDays = 14,
                dryRun = false,
                vacuumAfterCleanup = true,
            ),
        )

        assertEquals(4, result.matchedRowCount)
        assertEquals(4, result.deletedRowCount)
        assertEquals(true, result.vacuum.executed)
        assertEquals(
            listOf("readWrite:start", "count", "delete", "readWrite:end", "vacuum"),
            calls,
        )
    }

    @Test
    fun `cleanup request rejects retention outside allowed range`() {
        listOf(0, 366).forEach { rawSnapshotDays ->
            assertFailsWith<IllegalArgumentException> {
                SnapshotCleanupRequest(
                    rawSnapshotDays = rawSnapshotDays,
                    dryRun = false,
                    vacuumAfterCleanup = false,
                )
            }
        }
    }

    @Test
    fun `sqlite dry run preserves rows and cleanup deletes only rows before cutoff`() = runBlocking {
        val databaseFile = createTempFile()
        val storage = StorageSettings.fromJdbcUrl("jdbc:sqlite:${databaseFile.absolutePathString()}")
        createStorageMigratorFactory().create(storage).migrate(storage)

        StorageConnectionFactory.connect(storage).use { connection ->
            val transactionRunner = ExposedTransactionRunner(connection.database)
            val snapshotRepository = ExposedDiskSnapshotRepository()
            val maintenanceRepository = ExposedSnapshotMaintenanceRepository()
            val useCase = TransactionalSnapshotMaintenanceUseCase(
                repository = maintenanceRepository,
                transactionRunner = transactionRunner,
                storageMaintenance = createStorageMaintenanceOperation(connection),
                clock = clock,
            )
            val cutoff = Instant.parse("2026-07-09T12:00:00Z")

            transactionRunner.readWrite {
                snapshotRepository.insert(testDiskSnapshot("before", cutoff.minusSeconds(1).toEpochMilli()))
                snapshotRepository.insert(testDiskSnapshot("at-cutoff", cutoff.toEpochMilli()))
                snapshotRepository.insert(testDiskSnapshot("after", cutoff.plusSeconds(1).toEpochMilli()))
            }

            val dryRun = useCase.cleanup(
                SnapshotCleanupRequest(
                    rawSnapshotDays = 30,
                    dryRun = true,
                    vacuumAfterCleanup = false,
                ),
            )
            assertEquals(1, dryRun.matchedRowCount)
            assertEquals(0, dryRun.deletedRowCount)
            assertEquals(3, transactionRunner.readOnly { snapshotRepository.findLatestNodes().single().devices.size })

            val cleanup = useCase.cleanup(
                SnapshotCleanupRequest(
                    rawSnapshotDays = 30,
                    dryRun = false,
                    vacuumAfterCleanup = false,
                ),
            )
            assertEquals(1, cleanup.deletedRowCount)
            assertEquals(2, transactionRunner.readOnly { snapshotRepository.findLatestNodes().single().devices.size })
        }
    }
}

private class RecordingSnapshotMaintenanceRepository(
    private val countResult: Long = 0,
    private val deleteResult: Long = 0,
    private val calls: MutableList<String> = mutableListOf(),
) : SnapshotMaintenanceRepository {
    var deleteCalls = 0
        private set

    override fun countSnapshotsBefore(cutoff: OffsetDateTime): Long {
        calls += "count"
        return countResult
    }

    override fun deleteSnapshotsBefore(cutoff: OffsetDateTime): Long {
        calls += "delete"
        deleteCalls += 1
        return deleteResult
    }
}

private class RecordingMaintenanceTransactionRunner(
    private val calls: MutableList<String> = mutableListOf(),
) : TransactionRunner {
    var readOnlyCalls = 0
        private set
    var readWriteCalls = 0
        private set

    override suspend fun <T> readOnly(block: suspend () -> T): T {
        readOnlyCalls += 1
        return block()
    }

    override suspend fun <T> readWrite(block: suspend () -> T): T {
        readWriteCalls += 1
        calls += "readWrite:start"
        return block().also { calls += "readWrite:end" }
    }
}

private class RecordingStorageMaintenanceOperation(
    private val calls: MutableList<String> = mutableListOf(),
) : StorageMaintenanceOperation {
    var vacuumCalls = 0
        private set

    override fun vacuum() {
        calls += "vacuum"
        vacuumCalls += 1
    }
}
