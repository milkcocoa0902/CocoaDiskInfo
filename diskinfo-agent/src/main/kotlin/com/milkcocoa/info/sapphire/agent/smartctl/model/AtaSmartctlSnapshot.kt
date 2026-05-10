package com.milkcocoa.info.sapphire.agent.smartctl.model

import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSctCapabilities
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSmartAttributes
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSmartData
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSmartErrorLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSmartSelectiveSelfTestLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.AtaSmartSelfTestLog
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartAtaVersion
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartFormFactor
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartInterfaceSpeed
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartSataVersion
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartTrim
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartWwn
import com.milkcocoa.info.sapphire.agent.smartctl.model.ata.SmartZonedDevice
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartDevice
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartLocalTime
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartPowerOnTime
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartStatus
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartSupport
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartTemperature
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.SmartUserCapacity
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.Smartctl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("model_family")
    override val modelFamily: String? = null,
    @SerialName("serial_number")
    override val serialNumber: String,
    @SerialName("wwn")
    val wwn: SmartWwn? = null,
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
    val formFactor: SmartFormFactor? = null,
    @SerialName("trim")
    val trim: SmartTrim? = null,
    @SerialName("zoned_device")
    val zonedDevice: SmartZonedDevice? = null,
    @SerialName("in_smartctl_database")
    val inSmartctlDatabase: Boolean? = null,
    @SerialName("ata_version")
    val ataVersion: SmartAtaVersion? = null,
    @SerialName("sata_version")
    val sataVersion: SmartSataVersion? = null,
    @SerialName("interface_speed")
    val interfaceSpeed: SmartInterfaceSpeed? = null,
    @SerialName("smart_support")
    override val smartSupport: SmartSupport,
    @SerialName("smart_status")
    override val smartStatus: SmartStatus,
    @SerialName("ata_smart_data")
    val ataSmartData: AtaSmartData? = null,
    @SerialName("ata_sct_capabilities")
    val ataSctCapabilities: AtaSctCapabilities? = null,
    @SerialName("ata_smart_attributes")
    val ataSmartAttributes: AtaSmartAttributes? = null,

    @SerialName("power_on_time")
    override val powerOnTime: SmartPowerOnTime,
    @SerialName("power_cycle_count")
    override val powerCycleCount: Int,
    @SerialName("temperature")
    override val temperature: SmartTemperature,
    @SerialName("ata_smart_error_log")
    val ataSmartErrorLog: AtaSmartErrorLog? = null,
    @SerialName("ata_smart_self_test_log")
    val ataSmartSelfTestLog: AtaSmartSelfTestLog? = null,
    @SerialName("ata_smart_selective_self_test_log")
    val ataSmartSelectiveSelfTestLog: AtaSmartSelectiveSelfTestLog? = null,

    ): SmartctlSnapshot