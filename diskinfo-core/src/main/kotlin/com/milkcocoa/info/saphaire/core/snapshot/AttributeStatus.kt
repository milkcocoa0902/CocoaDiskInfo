package com.milkcocoa.info.saphaire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
enum class AttributeStatus {
    GOOD,
    CAUTION,
    BAD,
    UNKNOWN
}