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
class HubSnapshotIngestUseCase(
    private val snapshotRepository: DiskSnapshotRepository,
    private val registryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
) {
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
data class NodeHeartbeatCommand(
    val nodeId: Uuid,
    val expectedCollectionIntervalSeconds: Long,
    val receivedAt: Instant,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

class HubHeartbeatUseCase(
    private val registryRepository: NodeAgentRegistryRepository,
    private val transactionRunner: TransactionRunner,
) {
    @OptIn(ExperimentalUuidApi::class)
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
