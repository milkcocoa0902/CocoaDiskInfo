package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ExposedDiskSnapshotRepository : DiskSnapshotRepository {
    @OptIn(ExperimentalUuidApi::class)
    override fun insert(snapshot: DiskSnapshot) {
        val nodeId = NodeIdentity.nodeId
        val nodeName = NodeIdentity.nodeName

        DiskSnapshotTable.insert {
            it[DiskSnapshotTable.nodeId] = nodeId
            it[DiskSnapshotTable.nodeName] = nodeName
            it[DiskSnapshotTable.collectTimeStamp] = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(snapshot.timestamp.toEpochMilliseconds()),
                ZoneOffset.UTC,
            )
            it[DiskSnapshotTable.deviceKey] = snapshot.deviceKey
            it[DiskSnapshotTable.deviceSerialName] = snapshot.serial
            it[DiskSnapshotTable.connectionProtocol] = snapshot.metricsSnapshot.protocol.name
            it[DiskSnapshotTable.deviceModel] = snapshot.model
            it[DiskSnapshotTable.devicePath] = snapshot.path
            it[DiskSnapshotTable.temperatureCelsius] = snapshot.temperatureCelsius?.toBigDecimal()
            it[DiskSnapshotTable.powerOnCycles] = snapshot.metricsSnapshot.universal.powerCycleCount
            it[DiskSnapshotTable.powerOnHours] = snapshot.powerOnHours

            when (val metrics = snapshot.metricsSnapshot) {
                is MetricsSnapshot.AtaMetricsSnapshot -> {
                    it[DiskSnapshotTable.ataReallocatedSectorCount] =
                        metrics.attributes.find { attribute ->
                            attribute.id == AtaSmartAttributeId.ReallocatedSectorCt
                        }?.value
                    it[DiskSnapshotTable.ataCurrentPendingSectorCount] =
                        metrics.attributes.find { attribute ->
                            attribute.id == AtaSmartAttributeId.CurrentPendingSector
                        }?.value
                    it[DiskSnapshotTable.ataOfflineUncorrectableCount] =
                        metrics.attributes.find { attribute ->
                            attribute.id == AtaSmartAttributeId.OfflineUncorrectable
                        }?.value
                    it[DiskSnapshotTable.ataUdmaCrcErrorCount] =
                        metrics.attributes.find { attribute ->
                            attribute.id == AtaSmartAttributeId.UdmaCrcErrorCount
                        }?.value
                }

                is MetricsSnapshot.NvmeMetricsSnapshot -> {
                    it[DiskSnapshotTable.nvmePercentageUsed] = metrics.percentageUsed
                    it[DiskSnapshotTable.nvmeAvailableSpare] = metrics.availableSpare
                    it[DiskSnapshotTable.nvmeMediaErrorCount] = metrics.mediaErrors
                    it[DiskSnapshotTable.nvmeDataUnitsWritten] = metrics.dataUnitsWritten
                    it[DiskSnapshotTable.nvmeDataUnitsRead] = metrics.dataUnitsRead
                }
            }

            it[DiskSnapshotTable.snapshotJson] = snapshot
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun findLatestNodes(): List<NodeSnapshot> =
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
                        .sortedBy { it.path },
                )
            }
            .sortedBy { it.nodeName }

    override fun findLatestByDeviceKey(deviceKey: String): DiskSnapshot? =
        DiskSnapshotTable
            .selectAll()
            .where { DiskSnapshotTable.deviceKey eq deviceKey }
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.get(DiskSnapshotTable.snapshotJson)

    @OptIn(ExperimentalUuidApi::class)
    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): NodeDeviceHistoryPayload {
        var condition: Op<Boolean> =
            (DiskSnapshotTable.nodeId eq nodeId) and (DiskSnapshotTable.deviceKey eq deviceKey)

        query.from?.let { from ->
            condition = condition and (DiskSnapshotTable.collectTimeStamp greaterEq from)
        }
        query.to?.let { to ->
            condition = condition and (DiskSnapshotTable.collectTimeStamp lessEq to)
        }

        val rows = DiskSnapshotTable
            .selectAll()
            .where { condition }
            .orderBy(
                DiskSnapshotTable.collectTimeStamp to when (query.order) {
                    HistoryOrder.ASC -> SortOrder.ASC
                    HistoryOrder.DESC -> SortOrder.DESC
                },
            )
            .limit(query.limit)
            .toList()

        return NodeDeviceHistoryPayload(
            nodeId = nodeId.toString(),
            nodeName = rows.firstOrNull()?.get(DiskSnapshotTable.nodeName).orEmpty(),
            deviceKey = deviceKey,
            snapshots = rows.map { it[DiskSnapshotTable.snapshotJson] },
        )
    }
}
