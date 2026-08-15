package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertResult
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotOrigin
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
data class IngestSnapshotCommand(
    val ingestId: Uuid,
    val origin: SnapshotOrigin,
    val snapshot: DiskSnapshot,
    val receivedAt: Instant,
)

typealias IngestSnapshotResult = SnapshotInsertResult

interface SnapshotIngestUseCase {
    suspend fun ingest(command: IngestSnapshotCommand): IngestSnapshotResult
}

@OptIn(ExperimentalUuidApi::class)
class TransactionalSnapshotIngestUseCase(
    private val repository: DiskSnapshotRepository,
    private val transactionRunner: TransactionRunner,
) : SnapshotIngestUseCase {
    override suspend fun ingest(command: IngestSnapshotCommand): IngestSnapshotResult =
        transactionRunner.readWrite {
            repository.insert(
                SnapshotPersistenceRecord(
                    ingestId = command.ingestId,
                    origin = command.origin,
                    snapshot = command.snapshot,
                    receivedAt = command.receivedAt,
                ),
            )
        }
}
