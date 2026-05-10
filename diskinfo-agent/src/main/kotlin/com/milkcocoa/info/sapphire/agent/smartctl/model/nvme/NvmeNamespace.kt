package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NvmeNamespace(
    val id: Int,
    val size: Size,
    val capacity: Capacity,
    val utilization: Utilization,
    @SerialName("formatted_lba_size")
    val formattedLbaSize: Int
) {
    @Serializable
    data class Size(
        val blocks: Long,
        val bytes: Long
    )

    @Serializable
    data class Capacity(
        val blocks: Long,
        val bytes: Long
    )

    @Serializable
    data class Utilization(
        val blocks: Long,
        val bytes: Long
    )
}
