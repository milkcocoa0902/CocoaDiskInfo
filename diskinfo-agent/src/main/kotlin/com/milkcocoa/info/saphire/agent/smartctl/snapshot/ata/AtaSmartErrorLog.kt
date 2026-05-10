package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

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
