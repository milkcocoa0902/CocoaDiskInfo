package com.milkcocoa.info.saphaire.core.snapshot

interface HealthRule<T : MetricsSnapshot> {
    fun evaluate(snapshot: T): List<AttributeEvaluation>
}