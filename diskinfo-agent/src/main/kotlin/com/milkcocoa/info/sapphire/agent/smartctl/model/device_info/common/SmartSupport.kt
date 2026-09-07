package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Availability and current enablement of SMART support for the device. */
@Serializable
@SerialName("smart_support")
data class SmartSupport(
    @SerialName("available")
    /** Whether the device exposes SMART capabilities. */
    val available: Boolean,
    @SerialName("enabled")
    /** Whether SMART is currently enabled on the device. */
    val enabled: Boolean,
)
