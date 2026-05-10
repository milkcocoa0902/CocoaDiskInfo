package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_zoned_device")
data class SmartZonedDevice(
    @SerialName("capabilities")
    val capabilities: String
)