package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentFailure
import com.milkcocoa.info.sapphire.agent.datastore.NodeAgentRegistryRepository
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotInsertStatus
import com.milkcocoa.info.sapphire.agent.datastore.SnapshotPersistenceRecord
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import java.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
/**
 * Stores an authenticated snapshot and updates node freshness/idempotency state atomically.
 * A duplicate ingest still records receipt; an inactive node fails the transaction so a
 * successful-looking snapshot cannot become invisible to freshness calculations.
 */
class HubSnapshotIngestUseCase(
    private val snapshotRepository: DiskSnapshotRepository,
    private val registryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
) {
    /**
     * Persists a new or idempotent snapshot and updates the active node registry in one
     * read-write transaction; an inactive node causes the transaction to fail and roll back.
     */
    suspend fun ingest(command: IngestSnapshotCommand): IngestSnapshotResult =
        transactionRunner.readWrite {
            val result = snapshotRepository.insert(
                SnapshotPersistenceRecord(
                    ingestId = command.ingestId,
                    origin = command.origin,
                    snapshot = command.snapshot,
                    receivedAt = command.receivedAt,
                ),
            )
            val registryUpdated = when (result.status) {
                SnapshotInsertStatus.STORED ->
                    registryRepository.recordStoredSnapshot(command.origin.nodeId, command.receivedAt)
                SnapshotInsertStatus.DUPLICATE ->
                    registryRepository.recordDuplicate(command.origin.nodeId, command.receivedAt)
            }
            // Snapshot and registry state must commit together. Otherwise a successful
            // ingest could remain permanently invisible to freshness calculations.
            check(registryUpdated) { "Node ${command.origin.nodeId} is not active in the registry." }
            result
        }
}

@OptIn(ExperimentalUuidApi::class)
/** Node liveness and optional collection error received from an authenticated Node Agent. */
data class NodeHeartbeatCommand(
    /** Authenticated node identity; never accepted from the request body. */
    val nodeId: Uuid,
    /** Positive cadence used to calculate stale state. */
    val expectedCollectionIntervalSeconds: Long,
    /** Hub receive timestamp. */
    val receivedAt: Instant,
    /** Optional error code; must be paired with [errorMessage]. */
    val errorCode: String? = null,
    /** Optional error message; must be paired with [errorCode]. */
    val errorMessage: String? = null,
)

/** Records heartbeat and current failure state in one registry transaction. */
class HubHeartbeatUseCase(
    private val registryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
) {
    @OptIn(ExperimentalUuidApi::class)
    /**
     * Records [command], rejecting inactive nodes and mismatched (null/non-null) error fields.
     * @throws IllegalStateException when the node is not active or error fields are incomplete.
     */
    suspend fun record(command: NodeHeartbeatCommand) {
        transactionRunner.readWrite {
            check(
                registryRepository.recordHeartbeat(
                    nodeId = command.nodeId,
                    expectedCollectionIntervalSeconds = command.expectedCollectionIntervalSeconds,
                    seenAt = command.receivedAt,
                ),
            ) { "Node ${command.nodeId} is not active in the registry." }

            if (command.errorCode != null || command.errorMessage != null) {
                check(command.errorCode != null && command.errorMessage != null) {
                    "Heartbeat error code and message must be provided together."
                }
                registryRepository.recordFailure(
                    nodeId = command.nodeId,
                    failure = NodeAgentFailure(
                        code = command.errorCode,
                        message = command.errorMessage,
                        failedAt = command.receivedAt,
                    ),
                )
            }
        }
    }
}
