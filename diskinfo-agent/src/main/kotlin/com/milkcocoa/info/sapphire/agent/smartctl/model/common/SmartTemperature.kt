package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_temperature")
data class SmartTemperature(
    @SerialName("current")
    val current: Int
)
