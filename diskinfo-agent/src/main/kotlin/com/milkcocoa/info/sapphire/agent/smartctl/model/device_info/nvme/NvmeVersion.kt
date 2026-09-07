package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

/** NVMe specification version represented both as smartctl text and a numeric value. */
@Serializable
data class NvmeVersion(
    val string: String,
    val value: Int
)
