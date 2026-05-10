package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
data class UniversalMetrics(
    val temperatureCelsius: Int?,
    val powerOnHours: Long?,
    val powerCycleCount: Long?,
    val percentageUsed: Int?,       // 寿命消費 (0-100)
    val totalBytesWritten: Long?,   // 総書込量 (Bytes)
    val totalBytesRead: Long?,      // 総読込量 (Bytes)
    val criticalWarningCount: Int?  // 重大な警告の合計数
)
