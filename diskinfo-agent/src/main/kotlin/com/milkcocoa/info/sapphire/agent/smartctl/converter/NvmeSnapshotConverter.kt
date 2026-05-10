package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.saphaire.core.snapshot.DiskHealth
import com.milkcocoa.info.saphaire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.saphaire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.NvmeSmartctlSnapshot
import kotlin.time.Instant

fun NvmeSmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
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
            percentageUsed = this.nvmeSmartHealthInformationLog.percentageUsed,
            mediaErrors = this.nvmeSmartHealthInformationLog.mediaErrors.toLong(),
            dataUnitsWritten = this.nvmeSmartHealthInformationLog.dataUnitsWritten,
            dataUnitsRead = this.nvmeSmartHealthInformationLog.dataUnitsRead
        )
    )
}
