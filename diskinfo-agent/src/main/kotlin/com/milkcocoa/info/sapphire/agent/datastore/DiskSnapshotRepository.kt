package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.ExperimentalUuidApi

class DiskSnapshotRepository {
    @OptIn(ExperimentalUuidApi::class)
    fun findLatestNodes(): List<NodeSnapshot> = transaction {
        DiskSnapshotTable
            .selectAll()
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .distinctBy { it[DiskSnapshotTable.nodeId] to it[DiskSnapshotTable.deviceKey] }
            .groupBy { it[DiskSnapshotTable.nodeId] to it[DiskSnapshotTable.nodeName] }
            .map { (node, rows) ->
                NodeSnapshot(
                    nodeId = node.first.toString(),
                    nodeName = node.second,
                    devices = rows
                        .map { it[DiskSnapshotTable.snapshotJson] }
                        .sortedBy { it.deviceKey },
                )
            }
            .sortedBy { it.nodeName }
    }

    fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? = transaction {
        DiskSnapshotTable
            .selectAll()
            .where { DiskSnapshotTable.deviceKey eq deviceKey }
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DiskSnapshotTable.snapshotJson)
    }
}
