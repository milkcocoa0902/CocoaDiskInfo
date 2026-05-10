package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("device")
data class SmartDevice(
    @SerialName("name")
    val name: String,
    @SerialName("info_name")
    val infoName: String,
    @SerialName("type")
    val type: String,
    @SerialName("protocol")
    val protocol: String
)