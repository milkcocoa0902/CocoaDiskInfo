package com.milkcocoa.info.sapphire.agent.smartctl.model.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Device descriptor shared by scan and device-info smartctl JSON responses. [protocol] is the
 * discriminator used by the snapshot polymorphic serializer and is expected to be
 * `ata` or `nvme` (case-insensitively) for supported devices.
 */
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
