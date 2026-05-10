package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_zoned_device")
data class SmartZonedDevice(
    @SerialName("capabilities")
    val capabilities: String
)