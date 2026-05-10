package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("interface_speed")
data class SmartInterfaceSpeed(
    @SerialName("max")
    val max: InterfaceSpeed,
    @SerialName("current")
    val current: InterfaceSpeed,
)


@Serializable
@SerialName("speed")
data class InterfaceSpeed(
    @SerialName("sata_value")
    val sataValue: Int,
    @SerialName("string")
    val string: String,
    @SerialName("units_per_second")
    val unitsPerSecond: Int,
    @SerialName("bits_per_unit")
    val bitsPerUnit: Long,
)