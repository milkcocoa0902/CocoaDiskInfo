package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartAttributes(
    val revision: Int,
    val table: List<AtaSmartAttribute>
)