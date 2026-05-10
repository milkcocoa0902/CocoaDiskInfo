package com.milkcocoa.info.saphire.agent.smartctl.snapshot

import com.milkcocoa.info.saphaire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.saphire.agent.smartctl.cmd.SmartCtlCommandResult
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.AtaSmartAttributes
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartAtaVersion
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartFormFactor
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartInterfaceSpeed
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartSataVersion
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartTrim
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartWwn
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata.SmartZonedDevice
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartDevice
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartLocalTime
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartPowerOnTime
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartStatus
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartSupport
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartTemperature
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.SmartUserCapacity
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.common.Smartctl
import com.milkcocoa.info.saphire.agent.smartctl.snapshot.nvme.NvmeSmartHealthInformationLog
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = SmartctlSnapshot.SmartctlSnapshotSerializer::class)
sealed interface SmartctlSnapshot{
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

    @Serializable
    data class AtaSmartctlSnapshot(
        @SerialName("json_format_version")
        override val jsonFormatVersion: List<Int>,
        @SerialName("smartctl")
        override val smartctl: Smartctl,
        @SerialName("local_time")
        override val localTime: SmartLocalTime,
        @SerialName("device")
        override val device: SmartDevice,
        @SerialName("model_name")
        override val modelName: String,
        @SerialName("serial_number")
        override val serialNumber: String,
        @SerialName("wwn")
        val wwn: SmartWwn,
        @SerialName("firmware_version")
        override val firmwareVersion: String,
        @SerialName("user_capacity")
        override val userCapacity: SmartUserCapacity,
        @SerialName("logical_block_size")
        override val logicalBlockSize: Int,
        @SerialName("physical_block_size")
        val physicalBlockSize: Int,
        @SerialName("rotation_rate")
        val rotationRate: Int,
        @SerialName("form_factor")
        val formFactor: SmartFormFactor,
        @SerialName("trim")
        val trim: SmartTrim,
        @SerialName("zoned_device")
        val zonedDevice: SmartZonedDevice? = null,
        @SerialName("in_smartctl_database")
        val inSmartctlDatabase: Boolean,
        @SerialName("ata_version")
        val ataVersion: SmartAtaVersion,
        @SerialName("sata_version")
        val sataVersion: SmartSataVersion,
        @SerialName("interface_speed")
        val interfaceSpeed: SmartInterfaceSpeed,
        @SerialName("smart_support")
        override val smartSupport: SmartSupport,
        @SerialName("smart_status")
        override val smartStatus: SmartStatus,
        // ata_smart_data
        // ata_sct_capabilities
        @SerialName("ata_smart_attributes")
        val ataSmartAttributes: AtaSmartAttributes,

        @SerialName("power_on_time")
        override val powerOnTime: SmartPowerOnTime,
        @SerialName("power_cycle_count")
        override val powerCycleCount: Int,
        @SerialName("temperature")
        override val temperature: SmartTemperature,
        // ata_smart_error_log
        // ata_smart_self_test_log
        // ata_smart_selective_self_test_log

    ): SmartctlSnapshot


    @Serializable
    data class NvmeSmartctlSnapshot(
        @SerialName("json_format_version")
        override val jsonFormatVersion: List<Int>,
        @SerialName("smartctl")
        override val smartctl: Smartctl,
        @SerialName("local_time")
        override val localTime: SmartLocalTime,
        @SerialName("device")
        override val device: SmartDevice,
        @SerialName("model_name")
        override val modelName: String,
        @SerialName("serial_number")
        override val serialNumber: String,
        @SerialName("firmware_version")
        override val firmwareVersion: String,
        // nvme_pci_vendor
        // nvme_ieee_oui_identifier
        // nvme_controller_id
        // nvme_version
        // nvme_number_of_namespaces
        // nvme_namespaces
        @SerialName("user_capacity")
        override val userCapacity: SmartUserCapacity,

        @SerialName("logical_block_size")
        override val logicalBlockSize: Int,

        @SerialName("smart_support")
        override val smartSupport: SmartSupport,
        @SerialName("smart_status")
        override val smartStatus: SmartStatus,
        // nvme_smart_health_information_log
        @SerialName("temperature")
        override val temperature: SmartTemperature,

        @SerialName("power_cycle_count")
        override val powerCycleCount: Int,

        @SerialName("power_on_time")
        override val powerOnTime: SmartPowerOnTime,

        @SerialName("nvme_smart_health_information_log")
        val nvmeSmartHealthInformationLog: NvmeSmartHealthInformationLog,
        // nvme_error_information_log
        // nvme_self_test_log
    ): SmartctlSnapshot



    object SmartctlSnapshotSerializer: JsonContentPolymorphicSerializer<SmartctlSnapshot>(SmartctlSnapshot::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SmartctlSnapshot> {
            val deviceType = element.jsonObject["device"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            val deviceProtocol = element.jsonObject["device"]?.jsonObject?.get("protocol")?.jsonPrimitive?.content

            return when(deviceProtocol?.lowercase()){
                "ata" -> AtaSmartctlSnapshot.serializer()
                "nvme" -> NvmeSmartctlSnapshot.serializer()
                else -> throw IllegalArgumentException("Unsupported device protocol: $deviceProtocol")
            }
        }
    }

}

fun SmartctlSnapshot.toDiskSnapshot(): DiskSnapshot {
    return DiskSnapshot(
        timestamp = TODO(),
        deviceKey = TODO(),
        path = TODO(),
        model = TODO(),
        serial = TODO(),
        capacityBytes = TODO(),
        temperatureCelsius = TODO(),
        powerOnHours = TODO(),
        health = TODO(),
        metricsSnapshot = TODO()
    )
}

private val json = Json { ignoreUnknownKeys = true }
fun SmartCtlCommandResult.translate() = json.decodeFromString<SmartctlSnapshot>(this.output)