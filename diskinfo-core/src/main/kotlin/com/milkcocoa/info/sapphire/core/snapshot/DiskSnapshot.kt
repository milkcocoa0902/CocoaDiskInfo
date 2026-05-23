package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.sapphire.core.ata.AtaHealthRule
import com.milkcocoa.info.sapphire.core.nvme.NvmeHealthRule
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class DiskSnapshot(
    val timestamp: Instant,
    val deviceKey: String,
    val path: String,
    val model: String?,
    val serial: String?,
    val capacityBytes: Long,
    val temperatureCelsius: Int?,
    val powerOnHours: Long?,
    val health: DiskHealth,
    val metricsSnapshot: MetricsSnapshot
) : LogStructure {
    val evaluations by lazy {
        when (metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> AtaHealthRule().evaluate(metricsSnapshot)
            is MetricsSnapshot.NvmeMetricsSnapshot -> NvmeHealthRule().evaluate(metricsSnapshot)
        }
    }

    override fun stringify(): String {
        val universal = metricsSnapshot.universal

        return buildString {
            appendLine("Disk Snapshot")
            appendLine("- timestamp: $timestamp")
            appendLine("- protocol: ${metricsSnapshot.protocol}")
            appendLine("- health: ${health.name}")
            appendLine()

            appendLine("Device")
            appendLine("- path: $path")
            appendLine("- key: $deviceKey")
            appendLine("- model: ${model.orDash()}")
            appendLine("- serial: ${serial.orDash()}")
            appendLine("- capacity: ${formatBytes(capacityBytes)} ($capacityBytes bytes)")
            appendLine()

            appendLine("Universal Metrics")
            appendLine("- temperature: ${temperatureCelsius?.let { "$it C" } ?: "-"}")
            appendLine("- power on hours: ${powerOnHours ?: "-"}")
            appendLine("- power cycles: ${universal.powerCycleCount ?: "-"}")
            appendLine("- percentage used: ${universal.percentageUsed?.let { "$it%" } ?: "-"}")
            appendLine("- total written: ${universal.totalBytesWritten?.let { formatBytes(it) } ?: "-"}")
            appendLine("- total read: ${universal.totalBytesRead?.let { formatBytes(it) } ?: "-"}")
            appendLine("- critical warnings: ${universal.criticalWarningCount ?: "-"}")
            appendLine()

            appendLine("Protocol Metrics")
            when (val metrics = metricsSnapshot) {
                is MetricsSnapshot.AtaMetricsSnapshot -> {
                    appendLine("- attributes: ${metrics.attributes.size}")
                    appendLine("- reallocated sector count: ${metrics.attributes.find { it.id == AtaSmartAttributeId.ReallocatedSectorCt }?.value ?: "-"}")
                    appendLine("- current pending sector: ${metrics.attributes.find { it.id == AtaSmartAttributeId.CurrentPendingSector }?.value ?: "-"}")
                    appendLine("- offline uncorrectable: ${metrics.attributes.find { it.id == AtaSmartAttributeId.OfflineUncorrectable }?.value ?: "-"}")
                    appendLine("- udma crc error count: ${metrics.attributes.find { it.id == AtaSmartAttributeId.UdmaCrcErrorCount }?.value ?: "-"}")
                }

                is MetricsSnapshot.NvmeMetricsSnapshot -> {
                    appendLine("- available spare: ${metrics.availableSpare?.let { "$it%" } ?: "-"}")
                    appendLine("- percentage used: ${metrics.percentageUsed?.let { "$it%" } ?: "-"}")
                    appendLine("- media errors: ${metrics.mediaErrors ?: "-"}")
                    appendLine("- data units written: ${metrics.dataUnitsWritten ?: "-"}")
                    appendLine("- data units read: ${metrics.dataUnitsRead ?: "-"}")
                }
            }
            appendLine()

            appendLine("Evaluations")
            if (evaluations.isEmpty()) {
                appendLine("- -")
            } else {
                evaluations.forEach {
                    appendLine("- ${it.key}: ${it.status.name} (value=${it.value ?: "-"}, threshold=${it.threshold ?: "-"}${it.reason?.let { reason -> ", reason=$reason" } ?: ""})")
                }
            }
        }.trimEnd()
    }

    private fun String?.orDash(): String = this ?: "-"

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "${bytes} B"
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var value = bytes.toDouble()
        var unitIndex = -1

        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }

        return "%.2f %s".format(value, units[unitIndex])
    }
}

interface AttributeMapper<T> {
    fun toAttributeMap(value: T): Map<String, Any?>
}

class DiskSnapshotMapper : AttributeMapper<DiskSnapshot> {
    override fun toAttributeMap(value: DiskSnapshot): Map<String, Any?> = buildMap {
        put("timestamp", value.timestamp.toString())
        put("device_key", value.deviceKey)
        put("path", value.path)
        put("model", value.model)
        put("serial", value.serial)
        put("capacity_bytes", value.capacityBytes)
        put("temperature_celsius", value.temperatureCelsius)
        put("power_on_hours", value.powerOnHours)
        put("health", value.health.name)

        when (val protocolSnapshot = value.metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> {
                put("protocol", protocolSnapshot.protocol.name)
            }

            is MetricsSnapshot.NvmeMetricsSnapshot -> {
                put("protocol", protocolSnapshot.protocol.name)
            }
        }

        put("evaluations", value.evaluations)
    }
}
