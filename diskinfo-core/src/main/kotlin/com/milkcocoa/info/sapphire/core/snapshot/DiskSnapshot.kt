package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.sapphire.core.ata.AtaHealthRule
import com.milkcocoa.info.sapphire.core.nvme.NvmeHealthRule
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
): LogStructure {
    val evaluations by lazy {
        when (metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> AtaHealthRule().evaluate(metricsSnapshot)
            is MetricsSnapshot.NvmeMetricsSnapshot -> NvmeHealthRule().evaluate(metricsSnapshot)
        }
    }

    override fun stringify(): String {
        return buildString {
            appendLine("#meta")
            appendLine("timestamp: ${timestamp}")
            appendLine("deviceKey: '$deviceKey'")
            appendLine("path: '$path'")
            appendLine("model: '$model'")
            appendLine("serial: '$serial'")
            appendLine("capacityBytes: $capacityBytes")
            appendLine("temperatureCelsius: $temperatureCelsius")
            appendLine("powerOnHours: $powerOnHours")
            appendLine("health: ${health.name}")
            appendLine("metricsSnapshot: ${metricsSnapshot.stringify()}")
        }
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