package com.milkcocoa.info.sapphire.client.presentation

import androidx.compose.ui.graphics.Color
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

/** Counts snapshots evaluated as [DiskHealth.CAUTION] or [DiskHealth.BAD]; GOOD and UNKNOWN are excluded. */
internal fun List<EvaluatedDiskSnapshot>.countWarnings(): Int {
    return count { it.health == DiskHealth.CAUTION || it.health == DiskHealth.BAD }
}

/** Displays the newest snapshot timestamp, or `--` when the list is empty. */
internal fun List<EvaluatedDiskSnapshot>.latestTimestamp(): String {
    return maxByOrNull { it.timestamp }?.timestampLabel() ?: "--"
}

/** Removes fractional seconds for compact desktop timeline labels. */
internal fun EvaluatedDiskSnapshot.timestampLabel(): String {
    return timestamp.toString().substringBefore('.')
}

/** Maps the metrics subtype to the protocol label shown in detail cards. */
internal fun EvaluatedDiskSnapshot.protocolName(): String {
    return when (metricsSnapshot) {
        is MetricsSnapshot.AtaMetricsSnapshot -> "ATA"
        is MetricsSnapshot.NvmeMetricsSnapshot -> "NVMe"
    }
}

/** Returns the shared semantic color for evaluated disk health. */
internal fun healthColor(health: DiskHealth): Color {
    return when (health) {
        DiskHealth.GOOD -> Color(0xFF62D6A4)
        DiskHealth.CAUTION -> Color(0xFFFBBF24)
        DiskHealth.BAD -> Color(0xFFFF6B6B)
        DiskHealth.UNKNOWN -> Color(0xFF9AA1A8)
    }
}

/** Returns the shared semantic color for an individual policy attribute status. */
internal fun attributeStatusColor(status: AttributeStatus): Color {
    return when (status) {
        AttributeStatus.GOOD -> Color(0xFF62D6A4)
        AttributeStatus.CAUTION -> Color(0xFFFBBF24)
        AttributeStatus.BAD -> Color(0xFFFF6B6B)
        AttributeStatus.UNKNOWN -> Color(0xFF9AA1A8)
    }
}

/** Applies desktop warning thresholds to Celsius temperature; null is unknown. */
internal fun temperatureColor(temperatureCelsius: Int?): Color {
    return when {
        temperatureCelsius == null -> Color(0xFF9AA1A8)
        temperatureCelsius >= 60 -> Color(0xFFFF6B6B)
        temperatureCelsius >= 50 -> Color(0xFFFBBF24)
        else -> Color(0xFF62D6A4)
    }
}

/** Applies desktop warning thresholds to percentage used; null is unknown. */
internal fun wearColor(percentageUsed: Int?): Color {
    return when {
        percentageUsed == null -> Color(0xFF9AA1A8)
        percentageUsed >= 90 -> Color(0xFFFF6B6B)
        percentageUsed >= 70 -> Color(0xFFFBBF24)
        else -> Color(0xFF62D6A4)
    }
}

/** Applies desktop warning thresholds to remaining lifetime; null is unknown. */
internal fun lifetimeRemainingColor(lifetimeRemainingPercent: Int?): Color {
    return when {
        lifetimeRemainingPercent == null -> Color(0xFF9AA1A8)
        lifetimeRemainingPercent <= 10 -> Color(0xFFFF6B6B)
        lifetimeRemainingPercent <= 20 -> Color(0xFFFBBF24)
        else -> Color(0xFF62D6A4)
    }
}

/** Highlights any non-zero critical warning count and treats null as unknown. */
internal fun warningColor(criticalWarningCount: Int?): Color {
    return when {
        criticalWarningCount == null -> Color(0xFF9AA1A8)
        criticalWarningCount > 0 -> Color(0xFFFF6B6B)
        else -> Color(0xFF62D6A4)
    }
}

/** Formats values at or above 1 KiB with binary (1024-based) units and one fractional digit; smaller values remain integer bytes. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var unitIndex = -1

    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }

    return "%.1f %s".format(value, units[unitIndex])
}
