package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.sapphire.agent.smartctl.model.AtaSmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.NvmeSmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.SmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.identity.DeviceKeyDeriver
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot

fun SmartctlSnapshot.toDiskSnapshot(deviceKeyDeriver: DeviceKeyDeriver): DiskSnapshot {
    return when (this) {
        is AtaSmartctlSnapshot -> this.toDiskSnapshot(deviceKeyDeriver)
        is NvmeSmartctlSnapshot -> this.toDiskSnapshot(deviceKeyDeriver)
    }
}
