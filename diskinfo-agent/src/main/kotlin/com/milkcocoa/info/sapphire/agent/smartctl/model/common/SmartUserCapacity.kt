package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("user_capacity")
data class SmartUserCapacity(
    @SerialName("blocks")
    val blocks: Long,
    @SerialName("bytes")
    val bytes: Long
)