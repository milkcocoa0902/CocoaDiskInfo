package com.milkcocoa.info.saphire.agent.smartctl.snapshot.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmeErrorInformationLog(
    val size: Int,
    val read: Int,
    val unread: Int
)
