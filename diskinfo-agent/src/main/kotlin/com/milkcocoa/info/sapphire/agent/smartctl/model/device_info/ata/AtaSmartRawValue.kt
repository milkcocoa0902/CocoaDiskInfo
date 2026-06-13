package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartRawValue(
    val value: Long,
    val string: String,
)