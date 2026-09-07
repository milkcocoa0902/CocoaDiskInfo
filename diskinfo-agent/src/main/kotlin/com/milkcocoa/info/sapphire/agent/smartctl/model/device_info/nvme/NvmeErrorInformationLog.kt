package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

/** NVMe error-information log summary; counts are controller-reported entries. */
@Serializable
data class NvmeErrorInformationLog(
    val size: Int,
    val read: Int,
    val unread: Int
)
