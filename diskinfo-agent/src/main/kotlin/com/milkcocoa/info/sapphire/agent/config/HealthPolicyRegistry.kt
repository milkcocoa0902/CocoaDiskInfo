package com.milkcocoa.info.sapphire.agent.config

import com.milkcocoa.info.sapphire.core.health.DefaultHealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicy

/**
 * The runtime selection boundary for built-in health policies.
 *
 * Configuration and CLI code deal in stable names; the rest of the agent receives
 * the domain policy selected here.  Keep this registry deliberately small until a
 * policy distribution/versioning contract exists.
 */
object HealthPolicyRegistry {
    private val policies: Map<String, HealthPolicy> = mapOf(
        DefaultHealthPolicy.policyName to DefaultHealthPolicy,
    )

    /** Stable names accepted by config, CLI, and environment sources. */
    val availablePolicyNames: List<String>
        get() = policies.keys.sorted()

    /**
     * Selects a policy by its exact stable name after trimming surrounding whitespace.
     *
     * @throws AgentConfigValidationException when the name is blank or unknown; the
     * message includes available names so operators can correct configuration directly.
     */
    fun select(name: String): HealthPolicy {
        val normalized = name.trim()
        if (normalized.isBlank()) {
            throw AgentConfigValidationException(
                "[health].policy must not be blank. Available policies: ${availablePolicyNames.joinToString()}."
            )
        }
        return policies[normalized]
            ?: throw AgentConfigValidationException(
                "[health].policy '$name' is unknown. Available policies: ${availablePolicyNames.joinToString()}."
            )
    }
}
