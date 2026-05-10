package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("smart_support")
data class SmartSupport(
    @SerialName("available")
    val available: Boolean,
    @SerialName("enabled")
    val enabled: Boolean,
)
