package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Timestamp metadata captured by smartctl when the device was queried. */
@Serializable
@SerialName("local_time")
data class SmartLocalTime(
    @SerialName("time_t")
    /** Unix epoch seconds (`time_t`), used as the normalized snapshot timestamp. */
    val time: Long,
    @SerialName("asctime")
    /** Human-readable local-time rendering supplied by smartctl. */
    val asctime: String
)
