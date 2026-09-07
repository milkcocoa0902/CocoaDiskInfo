package com.milkcocoa.info.sapphire.agent.smartctl.model.nvme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Capacity and utilization information for one NVMe namespace. */
@Serializable
data class NvmeNamespace(
    val id: Int,
    val size: Size,
    val capacity: Capacity,
    val utilization: Utilization,
    @SerialName("formatted_lba_size")
    val formattedLbaSize: Int
) {
    /** Namespace size in logical blocks and bytes. */
    @Serializable
    data class Size(
        val blocks: Long,
        val bytes: Long
    )

    /** Namespace capacity as reported by the controller. */
    @Serializable
    data class Capacity(
        val blocks: Long,
        val bytes: Long
    )

    /** Namespace utilization as reported by the controller. */
    @Serializable
    data class Utilization(
        val blocks: Long,
        val bytes: Long
    )
}
