package com.milkcocoa.info.sapphire.core.health

import com.milkcocoa.info.sapphire.core.ata.AtaHealthRule
import com.milkcocoa.info.sapphire.core.nvme.NvmeHealthRule
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

/** The built-in policy that preserves the pre-policy health behavior. */
object DefaultHealthPolicy : HealthPolicy(
    policyName = "default",
    policyVersion = 2,
) {
    const val POLICY_NAME: String = "default"
    const val POLICY_VERSION: Int = 2

    override fun evaluate(snapshot: DiskSnapshot): HealthPolicyResult {
        val evaluations = when (val metrics = snapshot.metricsSnapshot) {
            is MetricsSnapshot.AtaMetricsSnapshot -> AtaHealthRule().evaluate(metrics)
            is MetricsSnapshot.NvmeMetricsSnapshot -> NvmeHealthRule().evaluate(metrics)
        }

        return HealthPolicyResult(
            reportedHealth = snapshot.health,
            overallHealth = snapshot.health,
            evaluations = evaluations,
        )
    }
}
