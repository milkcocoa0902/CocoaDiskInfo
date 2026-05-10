package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmeVersion(
    val string: String,
    val value: Int
)
