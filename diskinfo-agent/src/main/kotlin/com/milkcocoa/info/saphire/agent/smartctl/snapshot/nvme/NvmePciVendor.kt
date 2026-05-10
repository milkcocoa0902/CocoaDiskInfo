package com.milkcocoa.info.saphire.agent.smartctl.snapshot.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmePciVendor(
    val id: Int,
    val subsystem_id: Int
)
