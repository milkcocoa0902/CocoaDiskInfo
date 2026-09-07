package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** SATA specification version reported by smartctl. */
@Serializable
@SerialName("smart_sata_version")
data class SmartSataVersion(
    @SerialName("string")
    val string: String,
    @SerialName("value")
    val value: Int,
)
