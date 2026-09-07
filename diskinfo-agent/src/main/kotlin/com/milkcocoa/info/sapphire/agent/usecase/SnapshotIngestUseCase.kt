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
/** Command carrying authenticated Hub receive metadata into persistence. */
data class IngestSnapshotCommand(
    /** Idempotency key supplied by the Node Agent. */
    val ingestId: Uuid,
    /** Authenticated origin; node identity must come from the principal, not body input. */
    val origin: SnapshotOrigin,
    /** Raw observed snapshot to persist. */
    val snapshot: DiskSnapshot,
    /** Hub receive time stored with the row and used for freshness metadata. */
    val receivedAt: Instant,
)

/** Repository insert result exposed as the ingest use-case result. */
typealias IngestSnapshotResult = SnapshotInsertResult

/** Persistence boundary for idempotent snapshot ingestion. */
interface SnapshotIngestUseCase {
    /**
     * Inserts [command] and returns stored/duplicate status.
     * Implementations must preserve ingest-id conflict semantics and transaction atomicity.
     */
    suspend fun ingest(command: IngestSnapshotCommand): IngestSnapshotResult
}

/** Executes snapshot ingestion in one read-write transaction. */
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
