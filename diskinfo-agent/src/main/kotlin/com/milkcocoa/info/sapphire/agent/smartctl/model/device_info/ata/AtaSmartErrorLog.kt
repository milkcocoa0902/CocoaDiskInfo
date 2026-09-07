package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

/** Summary of the ATA SMART error log; detailed entries are not modeled here. */
@Serializable
data class AtaSmartErrorLog(
    val summary: Summary
) {
    /** Revision and entry counts reported by the ATA error log. */
    @Serializable
    data class Summary(
        val revision: Int,
        val count: Int
    )
}
