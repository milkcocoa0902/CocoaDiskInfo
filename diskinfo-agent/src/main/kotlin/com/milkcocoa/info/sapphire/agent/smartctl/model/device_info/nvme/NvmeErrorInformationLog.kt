package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.Serializable

@Serializable
data class NvmeErrorInformationLog(
    val size: Int,
    val read: Int,
    val unread: Int
)
