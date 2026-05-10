package com.milkcocoa.info.sapphire.agent.smartctl.converter

import com.milkcocoa.info.saphaire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.SmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.AtaSmartctlSnapshot
import com.milkcocoa.info.sapphire.agent.smartctl.model.NvmeSmartctlSnapshot

fun SmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
    return when (this) {
        is AtaSmartctlSnapshot -> this.toDiskSnapshot()
        is NvmeSmartctlSnapshot -> this.toDiskSnapshot()
    }
}
