package com.milkcocoa.info.sapphire.core.health

import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import kotlinx.serialization.Serializable

/**
 * Pure domain policy that derives a display health view from an observed snapshot.
 *
 * Implementations own their name and version. They must not read configuration or
 * perform I/O while evaluating a snapshot.
 */
abstract class HealthPolicy(
    /** Stable policy identifier persisted with evaluated output. */
    policyName: String,
    /** Positive policy revision used to distinguish changed evaluation semantics. */
    policyVersion: Int,
) {
    /** Validated stable policy identifier. */
    val policyName: String = policyName.also {
        require(it.isNotBlank()) { "Health policy name must not be blank." }
    }
    /** Validated positive revision of this policy's evaluation semantics. */
    val policyVersion: Int = policyVersion.also {
        require(it > 0) { "Health policy version must be positive." }
    }

    /** Serializable provenance attached to every result produced by this policy. */
    val metadata: HealthPolicyMetadata = HealthPolicyMetadata(policyName, policyVersion)

    /** Evaluates one immutable observation without reading configuration or performing I/O. */
    abstract fun evaluate(snapshot: DiskSnapshot): HealthPolicyResult
}

@Serializable
/** Serializable identity of the policy that produced an evaluated view. */
data class HealthPolicyMetadata(
    /** Stable policy name. */
    val policyName: String,
    /** Positive policy revision. */
    val policyVersion: Int,
) {
    init {
        require(policyName.isNotBlank()) { "Health policy name must not be blank." }
        require(policyVersion > 0) { "Health policy version must be positive." }
    }
}

/** Complete output of evaluating one [DiskSnapshot]. */
data class HealthPolicyResult(
    /** Health reported by the source snapshot before this policy's interpretation. */
    val reportedHealth: DiskHealth,
    /** Health exposed to consumers after policy evaluation. */
    val overallHealth: DiskHealth,
    /** Explainable per-indicator results with unique rule keys. */
    val evaluations: List<AttributeEvaluation>,
) {
    /** Alias used by evaluated read-model mappers. */
    val health: DiskHealth
        get() = overallHealth

    init {
        require(evaluations.map(AttributeEvaluation::ruleKey).distinct().size == evaluations.size) {
            "Health policy evaluations must use unique rule keys."
        }
    }
}
