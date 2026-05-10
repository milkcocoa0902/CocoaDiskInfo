package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

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