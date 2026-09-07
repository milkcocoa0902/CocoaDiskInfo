package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import kotlinx.serialization.Serializable

@Serializable
/** One raw ATA SMART table row as reported by the device. */
data class AtaAttribute(
    /** SMART identifier, including [AtaSmartAttributeId.Dynamic] for unknown/vendor IDs, and its policy mode. */
    val id: AtaSmartAttributeId,
    /** Display label associated with [id]; may be blank on incomplete input. */
    val name: String,
    /** Normalized SMART value, conventionally in the device's 1–253 scale. */
    val value: Int,
    /** Lowest normalized value recorded by the device. */
    val worst: Int,
    /** Device-reported failure threshold; zero is non-actionable for normalized rules. */
    val threshold: Int,
    /** Vendor/device-specific raw counter or measurement. */
    val rawValue: Long,
    /** Original textual raw value, retained for diagnostics and future parsing. */
    val rawString: String
)
