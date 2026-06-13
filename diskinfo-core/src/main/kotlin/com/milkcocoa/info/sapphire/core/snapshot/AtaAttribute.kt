package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import kotlinx.serialization.Serializable

@Serializable
data class AtaAttribute(
    val id: AtaSmartAttributeId,
    val name: String,
    val value: Int,
    val worst: Int,
    val threshold: Int,
    val rawValue: Long,
    val rawString: String
)
