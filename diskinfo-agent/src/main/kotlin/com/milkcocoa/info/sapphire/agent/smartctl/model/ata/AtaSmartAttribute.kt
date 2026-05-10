package com.milkcocoa.info.sapphire.agent.smartctl.model.ata

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartAttribute(
    val id: AtaSmartAttributeId,
    val value: Int,
    val worst: Int,
    val thresh: Int,
    @SerialName("when_failed")
    val whenFailed: String,
    val flags: AtaSmartAttributeFlag,
    val raw: AtaSmartRawValue,
)