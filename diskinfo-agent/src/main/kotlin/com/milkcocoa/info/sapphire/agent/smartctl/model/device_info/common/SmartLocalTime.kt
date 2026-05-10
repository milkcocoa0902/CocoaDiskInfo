package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("local_time")
data class SmartLocalTime(
    @SerialName("time_t")
    val time: Long,
    @SerialName("asctime")
    val asctime: String
)
