package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartAttributes(
    val revision: Int,
    val table: List<AtaSmartAttribute>
)