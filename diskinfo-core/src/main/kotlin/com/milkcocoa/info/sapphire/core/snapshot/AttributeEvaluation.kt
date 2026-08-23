package com.milkcocoa.info.sapphire.core.snapshot

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
data class AttributeEvaluation(
    val key: String,
    val value: Long?,
    val status: AttributeStatus,
    val threshold: Long?,
    val reason: String?,
    /**
     * Canonical identity for the rule.  Keep this additive and at the end of
     * the constructor so existing positional callers and [copy] calls using
     * the original five fields remain source-compatible.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val ruleKey: String = key,
) {
    init {
        require(key.isNotBlank()) { "Attribute evaluation key must not be blank." }
        require(ruleKey.isNotBlank()) { "Attribute evaluation rule key must not be blank." }
        require(!reason.isNullOrBlank()) { "Attribute evaluation reason must not be blank." }
    }
}
