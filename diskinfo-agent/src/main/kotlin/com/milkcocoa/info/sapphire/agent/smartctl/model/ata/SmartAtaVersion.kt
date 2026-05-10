package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_ata_version")
data class SmartAtaVersion(
    @SerialName("string")
    val string: String,
    @SerialName("major_value")
    val majorValue: Int,
    @SerialName("minor_value")
    val minorValue: Int,
)
