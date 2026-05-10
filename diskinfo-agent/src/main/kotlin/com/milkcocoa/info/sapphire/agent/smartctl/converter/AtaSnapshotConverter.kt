package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.saphaire.core.snapshot.DiskHealth
import com.milkcocoa.info.saphaire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.saphaire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.AtaSmartctlSnapshot
import kotlin.time.Instant

fun AtaSmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
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
        metricsSnapshot = MetricsSnapshot.AtaMetricsSnapshot(
            reallocatedSectors = this.ataSmartAttributes.table.find { it.id.id == 5 }?.raw?.value,
            currentPendingSectors = this.ataSmartAttributes.table.find { it.id.id == 197 }?.raw?.value,
            offlineUncorrectable = this.ataSmartAttributes.table.find { it.id.id == 198 }?.raw?.value,
            udmaCrcErrorCount = this.ataSmartAttributes.table.find { it.id.id == 199 }?.raw?.value
        )
    )
}
