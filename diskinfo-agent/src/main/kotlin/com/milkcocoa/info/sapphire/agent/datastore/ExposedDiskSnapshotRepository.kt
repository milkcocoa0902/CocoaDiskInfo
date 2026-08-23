package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryAlias
import org.jetbrains.exposed.v1.core.RowNumber
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExposedDiskSnapshotRepository : DiskSnapshotRepository {
    @OptIn(ExperimentalUuidApi::class)
    override fun insert(record: SnapshotPersistenceRecord): SnapshotInsertResult {
        findByIngestId(record.origin.nodeId, record.ingestId)?.let { existing ->
            return existing.toInsertResult(record)
        }

        // A PostgreSQL transaction cannot be queried after a unique violation. Ignoring
        // the race here lets us inspect the winning row in the same transaction.
        val statement = DiskSnapshotTable.insertIgnore {
            it[DiskSnapshotTable.ingestId] = record.ingestId
            it[DiskSnapshotTable.nodeId] = record.origin.nodeId
            it[DiskSnapshotTable.nodeName] = record.origin.nodeName
            it[DiskSnapshotTable.collectTimeStamp] = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(record.snapshot.timestamp.toEpochMilliseconds()),
                ZoneOffset.UTC,
            )
            it[DiskSnapshotTable.receivedAt] = OffsetDateTime.ofInstant(record.receivedAt, ZoneOffset.UTC)
            it[DiskSnapshotTable.deviceKey] = record.snapshot.deviceKey
            it[DiskSnapshotTable.deviceSerialName] = record.snapshot.serial
            it[DiskSnapshotTable.connectionProtocol] = record.snapshot.metricsSnapshot.protocol.name
            it[DiskSnapshotTable.deviceModel] = record.snapshot.model
            it[DiskSnapshotTable.devicePath] = record.snapshot.path
            it[DiskSnapshotTable.temperatureCelsius] = record.snapshot.temperatureCelsius?.toBigDecimal()
            it[DiskSnapshotTable.powerOnCycles] = record.snapshot.metricsSnapshot.universal.powerCycleCount
            it[DiskSnapshotTable.powerOnHours] = record.snapshot.powerOnHours

            when (val metrics = record.snapshot.metricsSnapshot) {
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

            it[DiskSnapshotTable.snapshotJson] = record.snapshot
        }

        if (statement.insertedCount == 1) {
            return SnapshotInsertResult(
                status = SnapshotInsertStatus.STORED,
                snapshotId = statement[DiskSnapshotTable.id].value,
                ingestId = record.ingestId,
                receivedAt = record.receivedAt,
            )
        }

        val winner = findByIngestId(record.origin.nodeId, record.ingestId)
            ?: error("Snapshot insert was ignored without an idempotency-key winner.")
        return winner.toInsertResult(record)
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun findLatestNodes(): List<RawNodeSnapshot> =
        DiskSnapshotTable
            .selectAll()
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .distinctBy { it[DiskSnapshotTable.nodeId] to it[DiskSnapshotTable.deviceKey] }
            .groupBy { it[DiskSnapshotTable.nodeId] to it[DiskSnapshotTable.nodeName] }
            .map { (node, rows) ->
                RawNodeSnapshot(
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
    override fun findLatest(nodeId: Uuid, deviceKey: String): StoredDiskSnapshot? =
        DiskSnapshotTable
            .selectAll()
            .where {
                (DiskSnapshotTable.nodeId eq nodeId) and
                    (DiskSnapshotTable.deviceKey eq deviceKey)
            }
            .orderBy(DiskSnapshotTable.collectTimeStamp to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toStoredSnapshot()

    override fun findLatestPage(request: LatestSnapshotPageRequest): LatestSnapshotPage {
        val latestRank = RowNumber()
            .over()
            .partitionBy(DiskSnapshotTable.nodeId, DiskSnapshotTable.deviceKey)
            .orderBy(
                DiskSnapshotTable.collectTimeStamp to SortOrder.DESC,
                DiskSnapshotTable.id to SortOrder.DESC,
            )
            .alias("latest_rank")
        val snapshotPayload = DiskSnapshotTable.snapshotJson.alias("snapshot_payload")
        val rankedSnapshots = DiskSnapshotTable
            .innerJoin(
                otherTable = NodeAgentRegistryTable,
                onColumn = { DiskSnapshotTable.nodeId },
                otherColumn = { NodeAgentRegistryTable.nodeId },
            )
            .select(
                DiskSnapshotTable.columns.filterNot { it == DiskSnapshotTable.snapshotJson } +
                    snapshotPayload + latestRank,
            )
            .where { NodeAgentRegistryTable.status eq NodeAgentStatus.ACTIVE.name }
            .alias("ranked_snapshot")

        var condition: Op<Boolean> = rankedSnapshots[latestRank] eq 1L
        request.cursor?.let { cursor ->
            condition = condition and (
                (rankedSnapshots[DiskSnapshotTable.nodeId] greater cursor.nodeId) or
                    (
                        (rankedSnapshots[DiskSnapshotTable.nodeId] eq cursor.nodeId) and
                            (rankedSnapshots[DiskSnapshotTable.deviceKey] greater cursor.deviceKey)
                        )
                )
        }

        val selectedSnapshotPayload = rankedSnapshots[snapshotPayload]
            .alias("selected_snapshot_payload")
        val selectedRows = rankedSnapshots
            .select(rankedSnapshots.columns + selectedSnapshotPayload)
            .where { condition }
            .orderBy(
                rankedSnapshots[DiskSnapshotTable.nodeId] to SortOrder.ASC,
                rankedSnapshots[DiskSnapshotTable.deviceKey] to SortOrder.ASC,
            )
            .limit(request.limit + 1)
            .toList()
        val hasMore = selectedRows.size > request.limit
        val rows = selectedRows
            .take(request.limit)
            .map { it.toStoredSnapshot(rankedSnapshots, selectedSnapshotPayload) }

        return LatestSnapshotPage(
            rows = rows,
            hasMore = hasMore,
            nextCursor = if (hasMore) {
                rows.lastOrNull()?.let { latest ->
                    LatestSnapshotCursor(
                        nodeId = latest.origin.nodeId,
                        deviceKey = latest.snapshot.deviceKey,
                    )
                }
            } else {
                null
            },
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun findHistory(
        nodeId: Uuid,
        deviceKey: String,
        query: HistoryQuery,
    ): RawNodeDeviceHistory {
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

        return RawNodeDeviceHistory(
            nodeId = nodeId.toString(),
            nodeName = rows.firstOrNull()?.get(DiskSnapshotTable.nodeName).orEmpty(),
            deviceKey = deviceKey,
            snapshots = rows.map { it[DiskSnapshotTable.snapshotJson] },
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun findByIngestId(nodeId: Uuid, ingestId: Uuid): StoredDiskSnapshot? =
        DiskSnapshotTable
            .selectAll()
            .where {
                (DiskSnapshotTable.nodeId eq nodeId) and
                    (DiskSnapshotTable.ingestId eq ingestId)
            }
            .limit(1)
            .singleOrNull()
            ?.toStoredSnapshot()

    @OptIn(ExperimentalUuidApi::class)
    private fun StoredDiskSnapshot.toInsertResult(
        record: SnapshotPersistenceRecord,
    ): SnapshotInsertResult {
        if (snapshot != record.snapshot) {
            throw IngestIdConflictException(
                nodeId = record.origin.nodeId,
                ingestId = record.ingestId,
            )
        }
        return SnapshotInsertResult(
            status = SnapshotInsertStatus.DUPLICATE,
            snapshotId = snapshotId,
            ingestId = ingestId,
            receivedAt = receivedAt,
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredSnapshot(): StoredDiskSnapshot =
        StoredDiskSnapshot(
            snapshotId = this[DiskSnapshotTable.id].value,
            ingestId = this[DiskSnapshotTable.ingestId],
            origin = SnapshotOrigin(
                nodeId = this[DiskSnapshotTable.nodeId],
                nodeName = this[DiskSnapshotTable.nodeName],
            ),
            snapshot = this[DiskSnapshotTable.snapshotJson],
            receivedAt = this[DiskSnapshotTable.receivedAt].toInstant(),
        )

    private fun org.jetbrains.exposed.v1.core.ResultRow.toStoredSnapshot(
        source: QueryAlias,
        snapshotPayload: ExpressionWithColumnType<DiskSnapshot>,
    ): StoredDiskSnapshot = StoredDiskSnapshot(
        snapshotId = this[source[DiskSnapshotTable.id]].value,
        ingestId = this[source[DiskSnapshotTable.ingestId]],
        origin = SnapshotOrigin(
            nodeId = this[source[DiskSnapshotTable.nodeId]],
            nodeName = this[source[DiskSnapshotTable.nodeName]],
        ),
        snapshot = this[snapshotPayload],
        receivedAt = this[source[DiskSnapshotTable.receivedAt]].toInstant(),
    )
}
