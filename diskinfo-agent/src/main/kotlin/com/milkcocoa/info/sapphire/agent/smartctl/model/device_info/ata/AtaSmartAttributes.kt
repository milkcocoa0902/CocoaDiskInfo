package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

/** ATA SMART attribute table and the table revision reported by the device. */
@Serializable
data class AtaSmartAttributes(
    val revision: Int,
    val table: List<AtaSmartAttribute>
)
