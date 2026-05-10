package com.milkcocoa.info.saphire.agent.smartctl.snapshot.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmeVersion(
    val string: String,
    val value: Int
)
