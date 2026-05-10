package com.milkcocoa.info.saphire.agent.smartctl.snapshot.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_power_on_time")
data class SmartPowerOnTime(
    @SerialName("hours")
    val hours: Int
)
