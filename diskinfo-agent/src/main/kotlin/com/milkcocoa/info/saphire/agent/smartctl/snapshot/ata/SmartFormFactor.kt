package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_form_factor")
data class SmartFormFactor(
    @SerialName("ata_value")
    val ataValue: Int,
    @SerialName("name")
    val name: String,
)
