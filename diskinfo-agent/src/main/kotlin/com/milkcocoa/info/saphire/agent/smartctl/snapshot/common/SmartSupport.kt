package com.milkcocoa.info.saphire.agent.smartctl.snapshot.common

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
