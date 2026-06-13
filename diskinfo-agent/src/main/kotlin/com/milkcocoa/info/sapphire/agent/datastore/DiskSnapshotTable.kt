package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import kotlin.uuid.ExperimentalUuidApi

private val j = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalUuidApi::class)
object DiskSnapshotTable: UuidTable(
    name = "disk_snapshot",
    columnName = "snapshot_id"
) {
    val nodeId = uuid("node_id").clientDefault { NodeIdentity.nodeId }
    val nodeName = varchar("node_name", 255).clientDefault { NodeIdentity.nodeName }

    val collectTimeStamp = timestampWithTimeZone("collect_time")
    val deviceKey = varchar("device_key", 255)
    val deviceSerialName = varchar("device_serial_name", 255).nullable()
    val connectionProtocol = varchar("connection_protocol", 255)
    val deviceModel = varchar("device_model", 255).nullable()
    val devicePath = varchar("device_path", 255)

    val temperatureCelsius = decimal("temperature_celsius", 5, 2).nullable()
    val powerOnHours = long("power_on_hours").nullable()
    val powerOnCycles = long("power_on_cycles").nullable()

    val ataReallocatedSectorCount = integer("ata_reallocated_sector_count").nullable()
    val ataCurrentPendingSectorCount = integer("ata_current_pending_sector_count").nullable()
    val ataOfflineUncorrectableCount = integer("ata_offline_uncorrectable_count").nullable()
    val ataUdmaCrcErrorCount = integer("ata_udma_crc_error_count").nullable()

    val nvmePercentageUsed = integer("nvme_percentage_used").nullable()
    val nvmeAvailableSpare = integer("nvme_available_spare").nullable()
    val nvmeMediaErrorCount = long("nvme_media_error_count").nullable()
    val nvmeDataUnitsWritten = long("nvme_data_units_written").nullable()
    val nvmeDataUnitsRead = long("nvme_data_units_read").nullable()

    init {
        index(false, nodeId, deviceKey, collectTimeStamp)
    }

    val snapshotJson = jsonb<DiskSnapshot>(
        "snapshot_json",
        serialize = { j.encodeToString(it) },
        deserialize = { j.decodeFromString<DiskSnapshot>(it) },
    )
}
