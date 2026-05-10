package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_trim")
data class SmartTrim(
    @SerialName("supported")
    val supported: Boolean,
)
