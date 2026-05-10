package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_power_on_time")
data class SmartPowerOnTime(
    @SerialName("hours")
    val hours: Int,
    @SerialName("minutes")
    val minutes: Int? = null
)
