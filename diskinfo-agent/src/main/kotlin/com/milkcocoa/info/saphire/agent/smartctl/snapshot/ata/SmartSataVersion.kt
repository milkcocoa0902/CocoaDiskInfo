package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_sata_version")
data class SmartSataVersion(
    @SerialName("string")
    val string: String,
    @SerialName("value")
    val value: Int,
)
