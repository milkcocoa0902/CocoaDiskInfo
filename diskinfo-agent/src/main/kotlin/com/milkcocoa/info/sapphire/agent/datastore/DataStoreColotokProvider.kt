package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.colotok.core.formatter.details.Formatter
import com.milkcocoa.info.colotok.core.formatter.details.TextFormatter
import com.milkcocoa.info.colotok.core.level.Level
import com.milkcocoa.info.colotok.core.level.LogLevel
import com.milkcocoa.info.colotok.core.logger.LogRecord
import com.milkcocoa.info.colotok.core.metrics.MetricsCollectorSpec
import com.milkcocoa.info.colotok.core.provider.details.Provider
import com.milkcocoa.info.colotok.core.provider.details.ProviderConfig
import com.milkcocoa.info.colotok.util.color.AnsiColor
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.ExperimentalUuidApi

val LogLevel.SmartMetrics by lazy {
    object : Level(
        levelInt = 0,
        name = "SmartMetrics",
        color = AnsiColor.BLACK
    ){}
}


class DataStoreColotokProvider: Provider(DataStoreColotokProviderConfig()) {
    class DataStoreColotokProviderConfig: ProviderConfig{
        override var formatter: Formatter = object: TextFormatter(""){}
        override var level: Level = LogLevel.SmartMetrics
        override var enableInternalMetricsLogging: Boolean = false
        override var metricsSpec: MetricsCollectorSpec = MetricsCollectorSpec.NoOp
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun onMessage(record: LogRecord) {
        when(record){
            is LogRecord.Pin -> Unit
            is LogRecord.PlainText -> Unit
            is LogRecord.Metrics -> Unit
            is LogRecord.StructuredText<*> if record.msg !is DiskSnapshot-> Unit
            is LogRecord.StructuredText<*> -> {
                // handle structured text
                val snapshot = (record.msg as DiskSnapshot)
                val nodeId = NodeIdentity.nodeId
                val nodeName = NodeIdentity.nodeName
                runCatching {
                    val id = transaction{
                        DiskSnapshotTable.insertAndGetId {
                            it[DiskSnapshotTable.nodeId] = nodeId
                            it[DiskSnapshotTable.nodeName] = nodeName
                            it[DiskSnapshotTable.collectTimeStamp] = OffsetDateTime.ofInstant(
                                Instant.ofEpochMilli(snapshot.timestamp.toEpochMilliseconds()),
                                ZoneId.systemDefault()
                            )
                            it[DiskSnapshotTable.deviceKey] = snapshot.deviceKey
                            it[DiskSnapshotTable.deviceSerialName] = snapshot.serial
                            it[DiskSnapshotTable.connectionProtocol] = snapshot.metricsSnapshot.protocol.name
                            it[DiskSnapshotTable.deviceModel] = snapshot.model
                            it[DiskSnapshotTable.devicePath] = snapshot.path
                            it[DiskSnapshotTable.temperatureCelsius] = snapshot.temperatureCelsius?.toBigDecimal()
                            it[DiskSnapshotTable.powerOnCycles] = snapshot.metricsSnapshot.universal.powerCycleCount
                            it[DiskSnapshotTable.powerOnHours] = snapshot.powerOnHours

                            when (val met = snapshot.metricsSnapshot) {
                                is MetricsSnapshot.AtaMetricsSnapshot -> {
                                    it[DiskSnapshotTable.ataReallocatedSectorCount] =
                                        met.attributes.find { it.id == AtaSmartAttributeId.ReallocatedSectorCt }?.value
                                    it[DiskSnapshotTable.ataCurrentPendingSectorCount] =
                                        met.attributes.find { it.id == AtaSmartAttributeId.CurrentPendingSector }?.value
                                    it[DiskSnapshotTable.ataOfflineUncorrectableCount] =
                                        met.attributes.find { it.id == AtaSmartAttributeId.OfflineUncorrectable }?.value
                                    it[DiskSnapshotTable.ataUdmaCrcErrorCount] =
                                        met.attributes.find { it.id == AtaSmartAttributeId.UdmaCrcErrorCount }?.value
                                }

                                is MetricsSnapshot.NvmeMetricsSnapshot -> {
                                    it[DiskSnapshotTable.nvmePercentageUsed] = met.percentageUsed
                                    it[DiskSnapshotTable.nvmeAvailableSpare] = met.availableSpare
                                    it[DiskSnapshotTable.nvmeMediaErrorCount] = met.mediaErrors
                                    it[DiskSnapshotTable.nvmeDataUnitsWritten] = met.dataUnitsWritten
                                    it[DiskSnapshotTable.nvmeDataUnitsRead] = met.dataUnitsRead
                                }
                            }

                            it[DiskSnapshotTable.snapshotJson] = snapshot
                        }
                    }
                }.getOrElse{
                }

            }
        }
    }
}
