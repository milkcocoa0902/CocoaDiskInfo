package com.milkcocoa.info.sapphire.agent.usecase

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotRepository
import com.milkcocoa.info.sapphire.agent.datastore.HistoryQuery
import com.milkcocoa.info.sapphire.agent.datastore.TransactionRunner
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface SnapshotUseCase {
    suspend fun saveSnapshot(snapshot: DiskSnapshot)

    suspend fun findLatestNodes(): List<NodeSnapshot>

    suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot?

    @OptIn(ExperimentalUuidApi::class)
    suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload
}

class TransactionalSnapshotUseCase(
    private val repository: DiskSnapshotRepository,
    private val transactionRunner: TransactionRunner,
) : SnapshotUseCase {
    override suspend fun saveSnapshot(snapshot: DiskSnapshot) {
        transactionRunner.readWrite {
            repository.insert(snapshot)
        }
    }

    override suspend fun findLatestNodes(): List<NodeSnapshot> =
        transactionRunner.readOnly {
            repository.findLatestNodes()
        }

    override suspend fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? =
        transactionRunner.readOnly {
            repository.findLatestByDeviceKey(deviceKey)
        }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload =
        transactionRunner.readOnly {
            repository.findHistory(
                nodeId = nodeId,
                deviceKey = deviceKey,
                query = query,
            )
        }
}
