package com.milkcocoa.info.sapphire.agent.datastore

import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.UuidTable
import org.jetbrains.exposed.v1.datetime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val j = Json { ignoreUnknownKeys = true }

object DiskSnapshotTable: UuidTable(
    name = "disk_snapshot",
    columnName = "snapshot_id"
) {
    @OptIn(ExperimentalUuidApi::class)
    val nodeId = uuid("node_id").clientDefault {
        val hostname = java.net.InetAddress.getLocalHost().hostName
        val namespace = UUID.nameUUIDFromBytes("com.milkcocoa.info.sapphire.node".toByteArray())
        
        val md = java.security.MessageDigest.getInstance("SHA-1")
        md.update(namespace.mostSignificantBits.toBytes())
        md.update(namespace.leastSignificantBits.toBytes())
        md.update(hostname.toByteArray())
        val bytes = md.digest()
        
        bytes[6] = (bytes[6].toInt() and 0x0f or 0x50).toByte()
        bytes[8] = (bytes[8].toInt() and 0x3f or 0x80).toByte()
        
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        Uuid.fromLongs(buffer.long, buffer.long)
    }

    private fun Long.toBytes(): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(8)
        buffer.putLong(this)
        return buffer.array()
    }

    val collectTimeStamp = timestampWithTimeZone("collect_time")
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

    val snapshotJson = jsonb<DiskSnapshot>(
        "snapshot_json",
        serialize = { j.encodeToString(it) },
        deserialize = { j.decodeFromString<DiskSnapshot>(it) },
    )
}