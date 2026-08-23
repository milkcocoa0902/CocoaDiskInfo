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
    policyName: String,
    policyVersion: Int,
) {
    val policyName: String = policyName.also {
        require(it.isNotBlank()) { "Health policy name must not be blank." }
    }
    val policyVersion: Int = policyVersion.also {
        require(it > 0) { "Health policy version must be positive." }
    }

    val metadata: HealthPolicyMetadata = HealthPolicyMetadata(policyName, policyVersion)

    abstract fun evaluate(snapshot: DiskSnapshot): HealthPolicyResult
}

@Serializable
data class HealthPolicyMetadata(
    val policyName: String,
    val policyVersion: Int,
) {
    init {
        require(policyName.isNotBlank()) { "Health policy name must not be blank." }
        require(policyVersion > 0) { "Health policy version must be positive." }
    }
}

data class HealthPolicyResult(
    val reportedHealth: DiskHealth,
    val overallHealth: DiskHealth,
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
