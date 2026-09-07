package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import kotlinx.serialization.Serializable

/** Vendor-encoded ATA SMART raw value, retained in numeric and display forms. */
@Serializable
data class AtaSmartRawValue(
    val value: Long,
    val string: String,
)
