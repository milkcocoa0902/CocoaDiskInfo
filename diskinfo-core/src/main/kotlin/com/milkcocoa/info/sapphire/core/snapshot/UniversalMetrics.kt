package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
/** Metrics normalized across ATA and NVMe where the source exposes them. */
data class UniversalMetrics(
    /** Current temperature in degrees Celsius, if reported. */
    val temperatureCelsius: Int?,
    /** Accumulated powered-on time in hours, if reported. */
    val powerOnHours: Long?,
    /** Number of power cycles, if reported. */
    val powerCycleCount: Long?,
    /** Estimated endurance consumed as a percentage; source values may exceed 100. */
    val percentageUsed: Int?,       // 寿命消費 (0-100)
    /** Estimated endurance remaining as a percentage, if the source provides it. */
    val lifetimeRemainingPercent: Int? = null, // 寿命残量 (0-100)
    /** Total write amount; normalized sources report bytes, while some ATA sources retain sectors. */
    val totalBytesWritten: Long?,   // 総書込量 (Bytes)
    /** Total read amount; normalized sources report bytes, while some ATA sources retain sectors. */
    val totalBytesRead: Long?,      // 総読込量 (Bytes)
    /** Protocol-specific warning count; NVMe uses critical warnings, while ATA aggregates selected error counters. */
    val criticalWarningCount: Int?  // 重大な警告の合計数
)
