package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartAttributeFlag(
    val value: Int,
    val string: String,
    val prefailure: Boolean,
    @SerialName("updated_online")
    val updatedOnline: Boolean,
    val performance: Boolean,
    @SerialName("error_rate")
    val errorRate: Boolean,
    @SerialName("event_count")
    val eventCount: Boolean,
    @SerialName("auto_keep")
    val autoKeep: Boolean,
)