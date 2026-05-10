package com.milkcocoa.info.saphire.agent.smartctl.snapshot.common

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