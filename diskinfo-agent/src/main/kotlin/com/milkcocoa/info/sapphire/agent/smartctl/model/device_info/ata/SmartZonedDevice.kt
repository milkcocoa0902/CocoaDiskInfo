package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** ATA zoned-device capabilities represented by smartctl's display string. */
@Serializable
@SerialName("smart_zoned_device")
data class SmartZonedDevice(
    @SerialName("capabilities")
    val capabilities: String
)
