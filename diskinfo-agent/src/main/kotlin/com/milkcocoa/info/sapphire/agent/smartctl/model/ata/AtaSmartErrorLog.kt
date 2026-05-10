package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartErrorLog(
    val summary: Summary
) {
    @Serializable
    data class Summary(
        val revision: Int,
        val count: Int
    )
}
