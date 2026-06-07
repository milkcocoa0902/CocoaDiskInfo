package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import com.milkcocoa.info.sapphire.agent.smartctl.model.AtaSmartctlSnapshot
import kotlin.time.Instant

fun AtaSmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
    val percentLifetimeRemaining = this.ataSmartAttributes
        ?.table
        ?.find { it.id == AtaSmartAttributeId.PercentLifetimeRemain }
        ?.value

    val universal = UniversalMetrics(
        temperatureCelsius = this.temperature.current,
        powerOnHours = this.powerOnTime.hours.toLong(),
        powerCycleCount = this.powerCycleCount.toLong(),
        percentageUsed = null,
        lifetimeRemainingPercent = percentLifetimeRemaining,
        totalBytesWritten = this.ataSmartAttributes?.table?.find { it.id == AtaSmartAttributeId.TotalLbasWritten }?.raw?.value, // total written in sectors
        totalBytesRead = this.ataSmartAttributes?.table?.find { it.id == AtaSmartAttributeId.TotalLbasRead }?.raw?.value,
        criticalWarningCount = this.ataSmartAttributes?.table?.filter { it.id in listOf(AtaSmartAttributeId.ReallocatedSectorCt, AtaSmartAttributeId.CurrentPendingSector, AtaSmartAttributeId.OfflineUncorrectable) }?.sumOf { it.raw.value }?.toInt() ?: 0
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
        metricsSnapshot = MetricsSnapshot.AtaMetricsSnapshot(
            universal = universal,
            attributes = this.ataSmartAttributes?.table?.map {
                AtaAttribute(
                    id = it.id,
                    name = it.id.name,
                    value = it.value,
                    worst = it.worst,
                    threshold = it.thresh,
                    rawValue = it.raw.value,
                    rawString = it.raw.string
                )
            } ?: emptyList()
        )
    )
}
