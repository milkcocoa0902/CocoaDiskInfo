package com.milkcocoa.info.sapphire.agent

import com.milkcocoa.info.sapphire.agent.datastore.DiskSnapshotTable
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal fun connectDiskSnapshotTestDatabase(): String {
    val databaseFile = createTempFile()
    val jdbcUrl = "jdbc:sqlite:${databaseFile.absolutePathString()}"

    Database.connect(jdbcUrl, "org.sqlite.JDBC")
    transaction {
        exec("DROP TABLE IF EXISTS disk_snapshot")
        MigrationUtils
            .statementsRequiredForDatabaseMigration(DiskSnapshotTable, withLogs = true)
            .forEach { exec(it) }
    }

    return jdbcUrl
}

@OptIn(ExperimentalUuidApi::class)
internal fun testNodeId(seed: Long): Uuid = Uuid.fromLongs(0L, seed)

internal fun testDiskSnapshot(
    deviceKey: String,
    timestampMillis: Long,
    temperatureCelsius: Int = 30,
    health: DiskHealth = DiskHealth.GOOD,
): DiskSnapshot {
    val universal = UniversalMetrics(
        temperatureCelsius = temperatureCelsius,
        powerOnHours = timestampMillis / 3_600_000,
        powerCycleCount = 10,
        percentageUsed = 5,
        lifetimeRemainingPercent = 95,
        totalBytesWritten = 1024L * 1024L,
        totalBytesRead = 512L * 1024L,
        criticalWarningCount = if (health == DiskHealth.GOOD) 0 else 1,
    )

    return DiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(timestampMillis),
        deviceKey = deviceKey,
        path = "/dev/$deviceKey",
        model = "Test Drive $deviceKey",
        serial = deviceKey,
        capacityBytes = 1_000_000_000_000,
        temperatureCelsius = temperatureCelsius,
        powerOnHours = universal.powerOnHours,
        health = health,
        metricsSnapshot = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = universal,
            percentageUsed = universal.percentageUsed,
            availableSpare = 100,
            mediaErrors = 0,
            dataUnitsWritten = 1,
            dataUnitsRead = 1,
        ),
    )
}

@OptIn(ExperimentalUuidApi::class)
internal fun insertDiskSnapshot(
    nodeId: Uuid,
    nodeName: String,
    snapshot: DiskSnapshot,
) {
    transaction {
        DiskSnapshotTable.insert {
            it[DiskSnapshotTable.nodeId] = nodeId
            it[DiskSnapshotTable.nodeName] = nodeName
            it[DiskSnapshotTable.collectTimeStamp] = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(snapshot.timestamp.toEpochMilliseconds()),
                ZoneOffset.UTC,
            )
            it[DiskSnapshotTable.deviceKey] = snapshot.deviceKey
            it[DiskSnapshotTable.deviceSerialName] = snapshot.serial
            it[DiskSnapshotTable.connectionProtocol] = snapshot.metricsSnapshot.protocol.name
            it[DiskSnapshotTable.deviceModel] = snapshot.model
            it[DiskSnapshotTable.devicePath] = snapshot.path
            it[DiskSnapshotTable.temperatureCelsius] = snapshot.temperatureCelsius?.toBigDecimal()
            it[DiskSnapshotTable.powerOnHours] = snapshot.powerOnHours
            it[DiskSnapshotTable.powerOnCycles] = snapshot.metricsSnapshot.universal.powerCycleCount

            val metrics = snapshot.metricsSnapshot as MetricsSnapshot.NvmeMetricsSnapshot
            it[DiskSnapshotTable.nvmePercentageUsed] = metrics.percentageUsed
            it[DiskSnapshotTable.nvmeAvailableSpare] = metrics.availableSpare
            it[DiskSnapshotTable.nvmeMediaErrorCount] = metrics.mediaErrors
            it[DiskSnapshotTable.nvmeDataUnitsWritten] = metrics.dataUnitsWritten
            it[DiskSnapshotTable.nvmeDataUnitsRead] = metrics.dataUnitsRead

            it[DiskSnapshotTable.snapshotJson] = snapshot
        }
    }
}
