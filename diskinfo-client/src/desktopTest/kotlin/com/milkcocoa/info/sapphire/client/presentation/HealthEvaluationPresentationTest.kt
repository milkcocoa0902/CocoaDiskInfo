package com.milkcocoa.info.sapphire.client.presentation

import com.milkcocoa.info.sapphire.client.api.LatestSnapshotPolicyMismatchException
import com.milkcocoa.info.sapphire.client.api.requireConsistentEvaluationPolicy
import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.ata.healthRuleKey
import com.milkcocoa.info.sapphire.core.api.NodeDeviceHistoryPayload
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HealthEvaluationPresentationTest {
    @Test
    fun `policy provenance distinguishes known and legacy responses`() {
        assertEquals("Current policy: default v1", HealthPolicyMetadata("default", 1).policyProvenanceText())
        assertEquals("Policy unknown (legacy server)", null.policyProvenanceText())
    }

    @Test
    fun `non-good reasons are severity ordered and format absent values`() {
        val snapshot = snapshot(
            health = DiskHealth.BAD,
            reportedHealth = DiskHealth.GOOD,
            evaluations = listOf(
                AttributeEvaluation("unknown", null, AttributeStatus.UNKNOWN, null, "Signal unavailable."),
                AttributeEvaluation("caution", 3, AttributeStatus.CAUTION, 2, "Near threshold."),
                AttributeEvaluation("bad", 4, AttributeStatus.BAD, 1, "Exceeded threshold."),
                AttributeEvaluation("good", 0, AttributeStatus.GOOD, 1, "Within threshold."),
            ),
        )

        assertEquals(listOf("bad", "caution", "unknown"), snapshot.nonGoodEvaluations().map { it.ruleKey })
        assertEquals(
            "unknown · value - · threshold - · Signal unavailable.",
            snapshot.nonGoodEvaluations().last().presentationText(),
        )
        assertEquals("Evaluated: BAD · Device reported: GOOD", snapshot.healthComparisonText())
    }

    @Test
    fun `ATA table uses server evaluation instead of SMART threshold fallback`() {
        val attribute = AtaAttribute(
            id = AtaSmartAttributeId.ReallocatedSectorCt,
            name = "Reallocated_Sector_Ct",
            value = 1,
            worst = 1,
            threshold = 1,
            rawValue = 1,
            rawString = "1",
        )
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))

        val rows = ataInformationRows(snapshot(metrics = metrics), metrics)

        assertEquals(AttributeStatus.UNKNOWN, rows.single().status)
    }

    @Test
    fun `ATA table uses canonical rule key when a legacy key alias differs`() {
        val attribute = AtaAttribute(
            id = AtaSmartAttributeId.ReallocatedSectorCt,
            name = "Reallocated_Sector_Ct",
            value = 0,
            worst = 100,
            threshold = 1,
            rawValue = 0,
            rawString = "0",
        )
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))
        val snapshot = snapshot(
            metrics = metrics,
            evaluations = listOf(
                AttributeEvaluation(
                    key = "legacy.reallocated",
                    ruleKey = "ata.reallocated_sector_count",
                    value = 7,
                    status = AttributeStatus.BAD,
                    threshold = 0,
                    reason = "Server policy marked this attribute bad.",
                ),
            ),
        )

        assertEquals(AttributeStatus.BAD, ataInformationRows(snapshot, metrics).single().status)
    }

    @Test
    fun `ATA table maps a known Dynamic alias to its canonical server rule key`() {
        val attribute = ataAttribute(
            AtaSmartAttributeId.Dynamic(5, "Reallocate_NAND_Blk_Cnt"),
        )
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))
        val snapshot = snapshot(
            metrics = metrics,
            evaluations = listOf(
                AttributeEvaluation(
                    key = "legacy.reallocate_nand_block_count",
                    ruleKey = "ata.reallocated_sector_count",
                    value = 1,
                    status = AttributeStatus.BAD,
                    threshold = 0,
                    reason = "Server policy marked this aliased attribute bad.",
                ),
            ),
        )

        assertEquals(AttributeStatus.BAD, ataInformationRows(snapshot, metrics).single().status)
    }

    @Test
    fun `ATA table renders Raw Read Error Rate from server evaluation`() {
        val attribute = ataAttribute(AtaSmartAttributeId.RawReadErrorRate)
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))
        val snapshot = snapshot(
            metrics = metrics,
            evaluations = listOf(
                AttributeEvaluation(
                    key = "legacy.raw_read_error_rate",
                    ruleKey = attribute.id.healthRuleKey(),
                    value = attribute.value.toLong(),
                    status = AttributeStatus.CAUTION,
                    threshold = attribute.threshold.toLong(),
                    reason = "Server policy marked this attribute caution.",
                ),
            ),
        )

        assertEquals(AttributeStatus.CAUTION, ataInformationRows(snapshot, metrics).single().status)
    }

    @Test
    fun `ATA table renders Spin Up Time from server evaluation`() {
        val attribute = ataAttribute(AtaSmartAttributeId.SpinUpTime)
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))
        val snapshot = snapshot(
            metrics = metrics,
            evaluations = listOf(
                AttributeEvaluation(
                    key = attribute.id.healthRuleKey(),
                    value = attribute.value.toLong(),
                    status = AttributeStatus.GOOD,
                    threshold = attribute.threshold.toLong(),
                    reason = "Server policy marked this attribute good.",
                ),
            ),
        )

        assertEquals(AttributeStatus.GOOD, ataInformationRows(snapshot, metrics).single().status)
    }

    @Test
    fun `ATA table renders Dynamic attributes using their server rule key`() {
        val attribute = ataAttribute(AtaSmartAttributeId.Dynamic(250, "Vendor_Custom"))
        val metrics = MetricsSnapshot.AtaMetricsSnapshot(universalMetrics(), listOf(attribute))
        val snapshot = snapshot(
            metrics = metrics,
            evaluations = listOf(
                AttributeEvaluation(
                    key = "legacy.vendor_custom",
                    ruleKey = attribute.id.healthRuleKey(),
                    value = attribute.value.toLong(),
                    status = AttributeStatus.BAD,
                    threshold = null,
                    reason = "Server policy marked this attribute bad.",
                ),
            ),
        )

        assertEquals(AttributeStatus.BAD, ataInformationRows(snapshot, metrics).single().status)
    }

    @Test
    fun `NVMe evaluations use the same protocol-neutral reason presentation`() {
        val snapshot = snapshot(
            evaluations = listOf(
                AttributeEvaluation("nvme.media_error_count", 2, AttributeStatus.CAUTION, 0, "Media errors reported."),
            ),
        )

        assertTrue(snapshot.nonGoodEvaluations().single().presentationText().contains("nvme.media_error_count"))
    }

    @Test
    fun `history accepts one policy and rejects a payload snapshot mismatch`() {
        val policy = HealthPolicyMetadata("default", 1)
        val matching = NodeDeviceHistoryPayload(
            nodeId = "node-a",
            nodeName = "Node A",
            deviceKey = "device-a",
            snapshots = listOf(snapshot(evaluationPolicy = policy)),
            evaluationPolicy = policy,
        )

        matching.requireConsistentEvaluationPolicy()

        val mismatch = matching.copy(evaluationPolicy = HealthPolicyMetadata("default", 2))
        kotlin.test.assertFailsWith<LatestSnapshotPolicyMismatchException> {
            mismatch.requireConsistentEvaluationPolicy()
        }
    }

    private fun snapshot(
        health: DiskHealth = DiskHealth.GOOD,
        reportedHealth: DiskHealth? = DiskHealth.GOOD,
        evaluations: List<AttributeEvaluation> = emptyList(),
        evaluationPolicy: HealthPolicyMetadata? = null,
        metrics: MetricsSnapshot = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = universalMetrics(),
            percentageUsed = null,
            availableSpare = null,
            mediaErrors = null,
            dataUnitsWritten = null,
            dataUnitsRead = null,
        ),
    ) = EvaluatedDiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(0),
        deviceKey = "device-a",
        path = "/dev/device-a",
        model = null,
        serial = null,
        capacityBytes = 1,
        temperatureCelsius = null,
        powerOnHours = null,
        health = health,
        metricsSnapshot = metrics,
        reportedHealth = reportedHealth,
        evaluations = evaluations,
        evaluationPolicy = evaluationPolicy,
    )

    private fun universalMetrics() = UniversalMetrics(
        temperatureCelsius = null,
        powerOnHours = null,
        powerCycleCount = null,
        percentageUsed = null,
        totalBytesWritten = null,
        totalBytesRead = null,
        criticalWarningCount = null,
    )

    private fun ataAttribute(id: AtaSmartAttributeId) = AtaAttribute(
        id = id,
        name = id.name,
        value = 100,
        worst = 90,
        threshold = 50,
        rawValue = 1,
        rawString = "1",
    )
}
