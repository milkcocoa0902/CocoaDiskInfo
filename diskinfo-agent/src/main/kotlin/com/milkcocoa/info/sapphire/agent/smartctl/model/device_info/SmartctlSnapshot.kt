package com.milkcocoa.info.sapphire.agent.smartctl.model

import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommandResponse
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


@Serializable(with = SmartctlSnapshot.SmartctlSnapshotSerializer::class)
sealed interface SmartctlSnapshot: SmartCtlCommandResponse {
    @SerialName("json_format_version")
    val jsonFormatVersion: List<Int>

    @SerialName("smartctl")
    val smartctl: Smartctl

    @SerialName("local_time")
    val localTime: SmartLocalTime

    @SerialName("device")
    val device: SmartDevice

    @SerialName("model_name")
    val modelName: String

    @SerialName("model_family")
    val modelFamily: String?

    @SerialName("serial_number")
    val serialNumber: String

    @SerialName("firmware_version")
    val firmwareVersion: String

    @SerialName("user_capacity")
    val userCapacity: SmartUserCapacity

    @SerialName("logical_block_size")
    val logicalBlockSize: Int

    @SerialName("smart_support")
    val smartSupport: SmartSupport

    @SerialName("smart_status")
    val smartStatus: SmartStatus

    @SerialName("temperature")
    val temperature: SmartTemperature

    @SerialName("power_cycle_count")
    val powerCycleCount: Int

    @SerialName("power_on_time")
    val powerOnTime: SmartPowerOnTime

    object SmartctlSnapshotSerializer : JsonContentPolymorphicSerializer<SmartctlSnapshot>(SmartctlSnapshot::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SmartctlSnapshot> {
            val deviceProtocol = element.jsonObject["device"]?.jsonObject?.get("protocol")?.jsonPrimitive?.content

            return when (deviceProtocol?.lowercase()) {
                "ata" -> AtaSmartctlSnapshot.serializer()
                "nvme" -> NvmeSmartctlSnapshot.serializer()
                else -> throw IllegalArgumentException("Unsupported device protocol: $deviceProtocol")
            }
        }
    }
}

