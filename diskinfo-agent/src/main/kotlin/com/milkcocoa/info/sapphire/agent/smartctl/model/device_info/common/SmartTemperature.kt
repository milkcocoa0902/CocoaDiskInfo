package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Temperature object from smartctl JSON; [current] is degrees Celsius. */
@Serializable
@SerialName("smart_temperature")
data class SmartTemperature(
    @SerialName("current")
    /** Current drive temperature in °C, as reported by smartctl. */
    val current: Int
)
