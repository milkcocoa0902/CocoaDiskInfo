package com.milkcocoa.info.saphire.agent.smartctl.snapshot.ata

import kotlinx.serialization.Serializable

@Serializable
data class AtaSmartRawValue(
    val value: Long,
    val string: String,
)