package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
enum class AttributeStatus {
    GOOD,
    CAUTION,
    BAD,
    UNKNOWN
}