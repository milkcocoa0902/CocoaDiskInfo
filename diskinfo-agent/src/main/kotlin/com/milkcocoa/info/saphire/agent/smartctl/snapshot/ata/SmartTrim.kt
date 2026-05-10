package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_trim")
data class SmartTrim(
    @SerialName("supported")
    val supported: Boolean,
)
