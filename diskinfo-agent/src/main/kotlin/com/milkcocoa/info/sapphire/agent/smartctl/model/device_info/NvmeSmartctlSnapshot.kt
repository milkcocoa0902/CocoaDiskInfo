package com.milkcocoa.info.sapphire.agent.smartctl.model

import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartDevice
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartLocalTime
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartPowerOnTime
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartStatus
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartSupport
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartTemperature
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartUserCapacity
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.Smartctl
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmeErrorInformationLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmeNamespace
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmePciVendor
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmeSelfTestLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmeSmartHealthInformationLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.nvme.NvmeVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("model_family")
    override val modelFamily: String? = null,
    @SerialName("serial_number")
    override val serialNumber: String,
    @SerialName("firmware_version")
    override val firmwareVersion: String,
    @SerialName("nvme_pci_vendor")
    val nvmePciVendor: NvmePciVendor,
    @SerialName("nvme_ieee_oui_identifier")
    val nvmeIeeeOuiIdentifier: Int,
    @SerialName("nvme_controller_id")
    val nvmeControllerId: Int,
    @SerialName("nvme_version")
    val nvmeVersion: NvmeVersion,
    @SerialName("nvme_number_of_namespaces")
    val nvmeNumberOfNamespaces: Int,
    @SerialName("nvme_namespaces")
    val nvmeNamespaces: List<NvmeNamespace>,
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
    @SerialName("nvme_error_information_log")
    val nvmeErrorInformationLog: NvmeErrorInformationLog,
    @SerialName("nvme_self_test_log")
    val nvmeSelfTestLog: NvmeSelfTestLog,
): SmartctlSnapshot