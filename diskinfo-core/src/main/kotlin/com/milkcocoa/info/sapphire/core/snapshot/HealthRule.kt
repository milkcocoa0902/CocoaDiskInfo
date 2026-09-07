package com.milkcocoa.info.sapphire.core.snapshot

/** Evaluates protocol-specific metrics without performing I/O or changing the snapshot. */
interface HealthRule<T : MetricsSnapshot> {
    /**
     * Produces one or more explainable evaluations for [snapshot].
     *
     * Implementations should preserve a stable [AttributeEvaluation.ruleKey] for each
     * logical indicator and use `UNKNOWN` when an absent value prevents safe evaluation.
     */
    fun evaluate(snapshot: T): List<AttributeEvaluation>
}
