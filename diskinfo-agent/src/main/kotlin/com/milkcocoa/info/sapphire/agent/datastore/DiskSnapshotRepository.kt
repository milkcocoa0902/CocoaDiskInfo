package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

interface DiskSnapshotRepository {
    fun insert(record: SnapshotPersistenceRecord): SnapshotInsertResult
    fun findLatestNodes(): List<NodeSnapshot>
    fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot?

    @OptIn(ExperimentalUuidApi::class)
    fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot?

    fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage

    @OptIn(ExperimentalUuidApi::class)
    fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload
}
