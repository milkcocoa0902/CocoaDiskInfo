package com.milkcocoa.info.saphaire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
data class AttributeEvaluation(
    val key: String,
    val value: Long?,
    val status: AttributeStatus,
    val threshold: Long?,
    val reason: String?
)