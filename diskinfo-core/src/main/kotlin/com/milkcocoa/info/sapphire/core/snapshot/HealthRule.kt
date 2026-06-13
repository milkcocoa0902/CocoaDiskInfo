package com.milkcocoa.info.sapphire.core.snapshot

interface HealthRule<T : MetricsSnapshot> {
    fun evaluate(snapshot: T): List<AttributeEvaluation>
}