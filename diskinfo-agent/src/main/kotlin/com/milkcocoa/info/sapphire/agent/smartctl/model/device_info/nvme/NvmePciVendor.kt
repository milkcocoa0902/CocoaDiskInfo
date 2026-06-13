package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmePciVendor(
    val id: Int,
    val subsystem_id: Int
)
