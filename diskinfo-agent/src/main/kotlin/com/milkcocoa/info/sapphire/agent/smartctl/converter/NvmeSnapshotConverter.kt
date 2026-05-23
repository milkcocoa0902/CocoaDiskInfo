package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import com.milkcocoa.info.sapphire.agent.smartctl.model.NvmeSmartctlSnapshot
import kotlin.time.Instant

fun NvmeSmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
    val universal = UniversalMetrics(
        temperatureCelsius = this.temperature.current,
        powerOnHours = this.powerOnTime.hours.toLong(),
        powerCycleCount = this.powerCycleCount.toLong(),
        percentageUsed = this.nvmeSmartHealthInformationLog.percentageUsed,
        totalBytesWritten = this.nvmeSmartHealthInformationLog.dataUnitsWritten * 512 * 1000, // 1000 sectors of 512 bytes
        totalBytesRead = this.nvmeSmartHealthInformationLog.dataUnitsRead * 512 * 1000,
        criticalWarningCount = this.nvmeSmartHealthInformationLog.criticalWarning
    )

    return DiskSnapshot(
        timestamp = Instant.fromEpochSeconds(this.localTime.time),
        deviceKey = this.serialNumber,
        path = this.device.name,
        model = this.modelName,
        serial = this.serialNumber,
        capacityBytes = this.userCapacity.bytes,
        temperatureCelsius = this.temperature.current,
        powerOnHours = this.powerOnTime.hours.toLong(),
        health = if (this.smartStatus.passed) DiskHealth.GOOD else DiskHealth.BAD,
        metricsSnapshot = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = universal,
            percentageUsed = this.nvmeSmartHealthInformationLog.percentageUsed,
            availableSpare = this.nvmeSmartHealthInformationLog.availableSpare,
            mediaErrors = this.nvmeSmartHealthInformationLog.mediaErrors.toLong(),
            dataUnitsWritten = this.nvmeSmartHealthInformationLog.dataUnitsWritten,
            dataUnitsRead = this.nvmeSmartHealthInformationLog.dataUnitsRead
        )
    )
}
