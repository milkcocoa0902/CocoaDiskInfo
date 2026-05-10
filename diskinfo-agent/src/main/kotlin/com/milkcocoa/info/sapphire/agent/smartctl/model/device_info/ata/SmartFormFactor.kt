package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

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
