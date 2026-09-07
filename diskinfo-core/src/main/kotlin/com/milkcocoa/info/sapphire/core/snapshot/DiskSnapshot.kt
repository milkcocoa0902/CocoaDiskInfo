package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
/** Immutable observation of one disk at a collection timestamp. */
data class DiskSnapshot(
    /** Time at which this observation was collected. */
    val timestamp: Instant,
    /** Stable local identity used to correlate observations across scans. */
    val deviceKey: String,
    /** Device path observed by the collector; it may change between boots. */
    val path: String,
    /** Human-readable device model, when reported. */
    val model: String?,
    /** Device serial number, when reported. */
    val serial: String?,
    /** Nominal capacity in bytes. */
    val capacityBytes: Long,
    /** Device temperature in degrees Celsius, when available. */
    val temperatureCelsius: Int?,
    /** Accumulated powered-on time in hours, when available. */
    val powerOnHours: Long?,
    /** Health value reported by the device or collector before policy evaluation. */
    val health: DiskHealth,
    /** Protocol-specific metrics captured with this observation. */
    val metricsSnapshot: MetricsSnapshot
) : LogStructure {
    /** Renders a stable human-readable diagnostic representation of this observation. */
    override fun stringify(): String {
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
        }.trimEnd()
    }
}

/** Converts a domain value into the key/value shape consumed by attribute-oriented output. */
interface AttributeMapper<T> {
    /** Returns implementation-defined attribute names and values for [value]. */
    fun toAttributeMap(value: T): Map<String, Any?>
}

/** Maps common disk fields and the protocol discriminator of a [DiskSnapshot]. */
class DiskSnapshotMapper : AttributeMapper<DiskSnapshot> {
    /** Produces snake_case keys suitable for logs and machine-readable attributes. */
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
    }
}
