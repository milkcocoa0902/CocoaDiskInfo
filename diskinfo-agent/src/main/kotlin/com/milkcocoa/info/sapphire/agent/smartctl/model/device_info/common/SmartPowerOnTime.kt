package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Accumulated power-on duration; [hours] is always present and [minutes] is optional. */
@Serializable
@SerialName("smart_power_on_time")
data class SmartPowerOnTime(
    @SerialName("hours")
    /** Whole hours reported by smartctl. */
    val hours: Int,
    @SerialName("minutes")
    /** Additional minutes when the device reports sub-hour precision; absent means unavailable. */
    val minutes: Int? = null
)
