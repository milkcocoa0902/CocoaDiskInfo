package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** User-visible capacity; [blocks] count [bytes] bytes at the reported logical block size. */
@Serializable
@SerialName("user_capacity")
data class SmartUserCapacity(
    @SerialName("blocks")
    /** Number of logical blocks in the user addressable area. */
    val blocks: Long,
    @SerialName("bytes")
    /** Capacity in bytes, as calculated and reported by smartctl. */
    val bytes: Long
)
