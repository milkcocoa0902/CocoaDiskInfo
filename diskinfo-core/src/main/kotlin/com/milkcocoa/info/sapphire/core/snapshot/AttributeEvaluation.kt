package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
/** Explainable result for one health indicator. */
data class AttributeEvaluation(
    /** Display-oriented metric key retained for compatibility with older clients. */
    val key: String,
    /** Observed value in the rule's native unit, or `null` when unavailable. */
    val value: Long?,
    /** Severity derived from [value] and the rule's thresholds. */
    val status: AttributeStatus,
    /** Threshold relevant to [status], in the same unit as [value], when applicable. */
    val threshold: Long?,
    /** Human-readable explanation; it is required to make the result actionable. */
    val reason: String?,
    /**
     * Canonical identity for the rule.  Keep this additive and at the end of
     * the constructor so existing positional callers and [copy] calls using
     * the original five fields remain source-compatible.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    /** Stable identity used for deduplication across policies and snapshots. */
    val ruleKey: String = key,
) {
    init {
        require(key.isNotBlank()) { "Attribute evaluation key must not be blank." }
        require(ruleKey.isNotBlank()) { "Attribute evaluation rule key must not be blank." }
        require(!reason.isNullOrBlank()) { "Attribute evaluation reason must not be blank." }
    }
}
