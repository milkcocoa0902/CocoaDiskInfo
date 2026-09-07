package com.milkcocoa.info.sapphire.core.health

import com.milkcocoa.info.sapphire.core.ata.AtaHealthRule
import com.milkcocoa.info.sapphire.core.nvme.NvmeHealthRule
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot

/** Built-in policy that preserves the source-reported aggregate health and adds rule details. */
object DefaultHealthPolicy : HealthPolicy(
    policyName = "default",
    policyVersion = 2,
) {
    /** Stable serialized policy name. */
    const val POLICY_NAME: String = "default"
    /** Revision for the current ATA/NVMe thresholds and rule-key contract. */
    const val POLICY_VERSION: Int = 2

    /** Evaluates protocol-specific indicators while retaining [snapshot.health] as the aggregate. */
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
