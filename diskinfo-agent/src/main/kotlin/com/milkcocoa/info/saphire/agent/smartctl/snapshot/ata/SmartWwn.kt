package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("wwn")
data class SmartWwn(
    @SerialName("naa")
    val naa: Int,
    @SerialName("oui")
    val oui: Int,
    @SerialName("id")
    val id: Long,
)
