package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import com.milkcocoa.info.sapphire.core.ata.AtaHealthRule
import com.milkcocoa.info.sapphire.core.nvme.NvmeHealthRule
import com.milkcocoa.info.sapphire.core.api.ResponsePayload
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
) : LogStructure, ResponsePayload {
    val evaluations by lazy {
        when (metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> AtaHealthRule().evaluate(metricsSnapshot)
            is MetricsSnapshot.NvmeMetricsSnapshot -> NvmeHealthRule().evaluate(metricsSnapshot)
        }
    }

    override fun stringify(): String {
        val nonGoodEvaluations = evaluations.filter { it.status != AttributeStatus.GOOD }

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
            appendLine("- capacity: ${capacityBytes.formatBytes()} ($capacityBytes bytes)")
            appendLine()

            appendLine(metricsSnapshot.stringify())
            appendLine()

            appendLine("Evaluations")
            appendLine("- checks: ${evaluations.size}")
            if (nonGoodEvaluations.isEmpty()) {
                appendLine("- result: all checks are GOOD")
            } else {
                appendLine("- non-good: ${nonGoodEvaluations.size}")
                nonGoodEvaluations.forEach {
                    appendLine("- ${it.key}: ${it.status.name} (value=${it.value ?: "-"}, threshold=${it.threshold ?: "-"})")
                    it.reason?.let { reason ->
                        appendLine("  reason: $reason")
                    }
                }
            }
        }.trimEnd()
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
