package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.Serializable

@Serializable
/** Severity assigned to one health-rule evaluation. */
enum class AttributeStatus {
    /** The observed value is within the rule's normal range. */
    GOOD,
    /** The value is usable but warrants attention before it becomes a failure. */
    CAUTION,
    /** The value violates the rule's safety threshold. */
    BAD,
    /** The value was absent or the rule could not safely interpret it. */
    UNKNOWN
}
