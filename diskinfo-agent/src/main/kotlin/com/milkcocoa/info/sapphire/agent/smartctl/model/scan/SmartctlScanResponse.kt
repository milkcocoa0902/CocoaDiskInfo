package com.milkcocoa.info.sapphire.agent.smartctl.model.scan

import com.milkcocoa.info.sapphire.agent.smartctl.cmd.SmartCtlCommandResponse
import com.milkcocoa.info.sapphire.agent.smartctl.model.common.Smartctl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SmartctlScanResponse(
    @SerialName("json_format_version")
    val jsonFormatVersion: List<Int>,
    val smartctl: Smartctl,
    val devices: List<SmartScanDevice>
) : SmartCtlCommandResponse

@Serializable
data class SmartScanDevice(
    val name: String,
    val info_name: String,
    val type: String,
    val protocol: String
)
