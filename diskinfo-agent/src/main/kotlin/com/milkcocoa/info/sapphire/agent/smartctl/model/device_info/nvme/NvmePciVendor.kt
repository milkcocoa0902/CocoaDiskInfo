package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

/** PCI vendor and subsystem identifiers for an NVMe controller. */
@Serializable
data class NvmePciVendor(
    val id: Int,
    val subsystem_id: Int
)
