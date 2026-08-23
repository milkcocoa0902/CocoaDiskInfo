package com.milkcocoa.info.sapphire.core.health

import com.milkcocoa.info.sapphire.core.ata.AtaSmartAttributeId
import com.milkcocoa.info.sapphire.core.ata.AtaAttributeEvaluationMode
import com.milkcocoa.info.sapphire.core.ata.healthRuleKey
import com.milkcocoa.info.sapphire.core.snapshot.AtaAttribute
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DefaultHealthPolicyTest {
    @Test
    fun `default policy has a stable identity and retains the reported overall health`() {
        val snapshot = nvmeSnapshot(
            reportedHealth = DiskHealth.CAUTION,
            percentageUsed = 100,
        )

        val result = DefaultHealthPolicy.evaluate(snapshot)

        assertEquals("default", DefaultHealthPolicy.policyName)
        assertEquals(2, DefaultHealthPolicy.policyVersion)
        assertEquals(HealthPolicyMetadata("default", 2), DefaultHealthPolicy.metadata)
        assertEquals(DiskHealth.CAUTION, result.reportedHealth)
        assertEquals(DiskHealth.CAUTION, result.overallHealth)
        assertEquals(result.overallHealth, result.health)
        assertEquals(AttributeStatus.BAD, result.evaluations.single { it.ruleKey == "nvme.percentage_used" }.status)
    }

    @Test
    fun `ATA lifetime remaining thresholds are stable`() {
        val cases = listOf(
            LifetimeCase(21, AttributeStatus.GOOD, 20),
            LifetimeCase(20, AttributeStatus.CAUTION, 20),
            LifetimeCase(11, AttributeStatus.CAUTION, 20),
            LifetimeCase(10, AttributeStatus.BAD, 10),
            LifetimeCase(9, AttributeStatus.BAD, 10),
        )

        cases.forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(
                ataSnapshot(
                    attributes = listOf(
                        ataAttribute(AtaSmartAttributeId.PercentLifetimeRemain, value = case.value),
                    ),
                ),
            )
                .evaluations
                .single { it.ruleKey == "ata.percent_lifetime_remaining" }

            assertEquals(case.status, evaluation.status, "lifetime remaining=${case.value}")
            assertEquals(case.threshold, evaluation.threshold, "lifetime remaining=${case.value}")
            assertEquals(case.value.toLong(), evaluation.value, "lifetime remaining=${case.value}")
            assertEquals(evaluation.ruleKey, evaluation.key)
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `ATA zero-value metric thresholds are stable`() {
        val cases = listOf(
            ZeroMetricCase(0, AttributeStatus.GOOD),
            ZeroMetricCase(1, AttributeStatus.BAD),
        )

        cases.forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(
                ataSnapshot(
                    attributes = listOf(reallocatedAttribute(case.value!!)),
                ),
            ).evaluations.single { it.ruleKey == "ata.reallocated_sector_count" }

            assertEquals(case.status, evaluation.status, "reallocated=${case.value}")
            assertEquals(0, evaluation.threshold)
            assertEquals(case.value, evaluation.value)
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `NVMe wear and spare thresholds are stable`() {
        val percentageUsedCases = listOf(
            ThresholdCase(null, AttributeStatus.UNKNOWN, null),
            ThresholdCase(79, AttributeStatus.GOOD, 80),
            ThresholdCase(80, AttributeStatus.CAUTION, 80),
            ThresholdCase(99, AttributeStatus.CAUTION, 80),
            ThresholdCase(100, AttributeStatus.BAD, 100),
        )
        val availableSpareCases = listOf(
            ThresholdCase(null, AttributeStatus.UNKNOWN, null),
            ThresholdCase(21, AttributeStatus.GOOD, 20),
            ThresholdCase(20, AttributeStatus.CAUTION, 20),
            ThresholdCase(11, AttributeStatus.CAUTION, 20),
            ThresholdCase(10, AttributeStatus.BAD, 10),
        )

        percentageUsedCases.forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(nvmeSnapshot(percentageUsed = case.value))
                .evaluations.single { it.ruleKey == "nvme.percentage_used" }
            assertEquals(case.status, evaluation.status, "percentage used=${case.value}")
            assertEquals(case.threshold, evaluation.threshold, "percentage used=${case.value}")
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
        availableSpareCases.forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(nvmeSnapshot(availableSpare = case.value))
                .evaluations.single { it.ruleKey == "nvme.available_spare" }
            assertEquals(case.status, evaluation.status, "available spare=${case.value}")
            assertEquals(case.threshold, evaluation.threshold, "available spare=${case.value}")
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `NVMe zero-value metric thresholds are stable`() {
        listOf(
            ZeroMetricCase(null, AttributeStatus.UNKNOWN),
            ZeroMetricCase(0, AttributeStatus.GOOD),
            ZeroMetricCase(1, AttributeStatus.BAD),
        ).forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(nvmeSnapshot(mediaErrors = case.value))
                .evaluations.single { it.ruleKey == "nvme.media_error_count" }
            assertEquals(case.status, evaluation.status, "media errors=${case.value}")
            assertEquals(0, evaluation.threshold)
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `evaluation order and keys are stable for each protocol`() {
        assertEquals(emptyList(), DefaultHealthPolicy.evaluate(ataSnapshot()).evaluations)
        assertEquals(
            listOf(
                "nvme.percentage_used",
                "nvme.available_spare",
                "nvme.media_error_count",
                "nvme.critical_warning_count",
            ),
            DefaultHealthPolicy.evaluate(nvmeSnapshot()).evaluations.map { it.ruleKey },
        )
    }

    @Test
    fun `ATA rule keys are stable for known special and dynamic attributes`() {
        assertEquals("ata.raw_read_error_rate", AtaSmartAttributeId.RawReadErrorRate.healthRuleKey())
        assertEquals("ata.throughput_performance", AtaSmartAttributeId.ThroughputPerformance.healthRuleKey())
        assertEquals("ata.spin_up_time", AtaSmartAttributeId.SpinUpTime.healthRuleKey())
        assertEquals("ata.reallocated_sector_count", AtaSmartAttributeId.ReallocatedSectorCt.healthRuleKey())
        assertEquals("ata.current_pending_sector_count", AtaSmartAttributeId.CurrentPendingSector.healthRuleKey())
        assertEquals("ata.offline_uncorrectable_count", AtaSmartAttributeId.OfflineUncorrectable.healthRuleKey())
        assertEquals("ata.udma_crc_error_count", AtaSmartAttributeId.UdmaCrcErrorCount.healthRuleKey())
        assertEquals("ata.percent_lifetime_remaining", AtaSmartAttributeId.PercentLifetimeRemain.healthRuleKey())
        assertEquals("ata.attribute_254", AtaSmartAttributeId.Dynamic(254, "Vendor_Attribute").healthRuleKey())
        assertEquals(
            "ata.reallocated_sector_count",
            AtaSmartAttributeId.of(5, "Reallocate_NAND_Blk_Cnt").healthRuleKey(),
        )
        assertEquals(
            "ata.current_pending_sector_count",
            AtaSmartAttributeId.of(197, "Current_Pending_ECC_Cnt").healthRuleKey(),
        )
    }

    @Test
    fun `ATA identities own evaluation modes including numeric aliases`() {
        assertEquals(AtaAttributeEvaluationMode.NormalizedThreshold, AtaSmartAttributeId.RawReadErrorRate.evaluationMode)
        assertEquals(AtaAttributeEvaluationMode.NormalizedThreshold, AtaSmartAttributeId.ThroughputPerformance.evaluationMode)
        assertEquals(AtaAttributeEvaluationMode.RawMaximum(0), AtaSmartAttributeId.ReallocatedSectorCt.evaluationMode)
        assertEquals(
            AtaAttributeEvaluationMode.RemainingPercentage(badAtOrBelow = 10, cautionAtOrBelow = 20),
            AtaSmartAttributeId.PercentLifetimeRemain.evaluationMode,
        )
        assertEquals(
            AtaAttributeEvaluationMode.RawMaximum(0),
            AtaSmartAttributeId.of(198, "Offline_Uncorrectable_ECC_Cnt").evaluationMode,
        )
        assertEquals(
            AtaAttributeEvaluationMode.RemainingPercentage(badAtOrBelow = 10, cautionAtOrBelow = 20),
            AtaSmartAttributeId.of(202, "Remaining_Lifetime_Percent").evaluationMode,
        )
        assertEquals(
            AtaAttributeEvaluationMode.NormalizedThreshold,
            AtaSmartAttributeId.Dynamic(254, "Vendor_Attribute").evaluationMode,
        )
    }

    @Test
    fun `ATA dynamic rule keys survive ID-only serialization`() {
        val original = AtaSmartAttributeId.Dynamic(246, "Vendor_Attribute")
        val restored = Json.decodeFromString<AtaSmartAttributeId>(
            Json.encodeToString<AtaSmartAttributeId>(original),
        )

        assertEquals(246, restored.id)
        assertEquals("ata.attribute_246", original.healthRuleKey())
        assertEquals(original.healthRuleKey(), restored.healthRuleKey())
    }

    @Test
    fun `ATA special dynamic aliases retain their canonical semantics after ID-only serialization`() {
        val aliases = listOf(
            AtaSmartAttributeId.Dynamic(5, "Reallocate_NAND_Blk_Cnt") to AtaAttributeEvaluationMode.RawMaximum(0),
            AtaSmartAttributeId.Dynamic(202, "Remaining_Lifetime_Percent") to
                AtaAttributeEvaluationMode.RemainingPercentage(badAtOrBelow = 10, cautionAtOrBelow = 20),
        )

        aliases.forEach { (original, expectedMode) ->
            val restored = Json.decodeFromString<AtaSmartAttributeId>(
                Json.encodeToString<AtaSmartAttributeId>(original),
            )

            assertEquals(original.canonicalHealthRuleKey, restored.canonicalHealthRuleKey)
            assertEquals(expectedMode, original.evaluationMode)
            assertEquals(expectedMode, restored.evaluationMode)
        }
    }

    @Test
    fun `ATA vendor aliases retain fixed raw zero rules by numeric ID`() {
        val reallocatedAlias = AtaSmartAttributeId.of(5, "Reallocate_NAND_Blk_Cnt")
        val pendingAlias = AtaSmartAttributeId.of(197, "Current_Pending_ECC_Cnt")
        val offlineAlias = AtaSmartAttributeId.of(198, "Offline_Uncorrectable_ECC_Cnt")
        val crcAlias = AtaSmartAttributeId.of(199, "SATA_CRC_Error_Count")
        val evaluations = DefaultHealthPolicy.evaluate(
            ataSnapshot(
                attributes = listOf(
                    ataAttribute(reallocatedAlias, rawValue = 1),
                    ataAttribute(pendingAlias, rawValue = 1),
                    ataAttribute(offlineAlias, rawValue = 1),
                    ataAttribute(crcAlias, rawValue = 1),
                ),
            ),
        ).evaluations

        assertEquals(
            AttributeStatus.BAD,
            evaluations.single { it.ruleKey == "ata.reallocated_sector_count" }.status,
        )
        assertEquals(
            AttributeStatus.BAD,
            evaluations.single { it.ruleKey == "ata.current_pending_sector_count" }.status,
        )
        assertEquals(
            AttributeStatus.BAD,
            evaluations.single { it.ruleKey == "ata.offline_uncorrectable_count" }.status,
        )
        assertEquals(
            AttributeStatus.BAD,
            evaluations.single { it.ruleKey == "ata.udma_crc_error_count" }.status,
        )
        assertEquals(evaluations.size, evaluations.map { it.ruleKey }.distinct().size)
    }

    @Test
    fun `ATA lifetime alias evaluates its observed normalized value`() {
        val lifetimeAlias = AtaSmartAttributeId.of(202, "Remaining_Lifetime_Percent")
        val evaluation = DefaultHealthPolicy.evaluate(
            ataSnapshot(
                attributes = listOf(ataAttribute(lifetimeAlias, value = 10, threshold = 0)),
            ),
        ).evaluations.single { it.ruleKey == "ata.percent_lifetime_remaining" }

        assertEquals(10, evaluation.value)
        assertEquals("ata.percent_lifetime_remaining", evaluation.ruleKey)
        assertEquals(10, evaluation.threshold)
        assertEquals(AttributeStatus.BAD, evaluation.status)
    }

    @Test
    fun `ATA vendor specific raw attributes use normalized device thresholds`() {
        val cases = listOf(
            NormalizedAttributeCase(
                id = AtaSmartAttributeId.RawReadErrorRate,
                value = 51,
                worst = 51,
                threshold = 50,
                rawValue = Long.MAX_VALUE,
                expectedStatus = AttributeStatus.GOOD,
                expectedThreshold = 50,
            ),
            NormalizedAttributeCase(
                id = AtaSmartAttributeId.SpinUpTime,
                value = 51,
                worst = 51,
                threshold = 50,
                rawValue = Long.MAX_VALUE,
                expectedStatus = AttributeStatus.GOOD,
                expectedThreshold = 50,
            ),
            NormalizedAttributeCase(
                id = AtaSmartAttributeId.SpinUpTime,
                value = 50,
                worst = 90,
                threshold = 50,
                rawValue = Long.MAX_VALUE,
                expectedStatus = AttributeStatus.BAD,
                expectedThreshold = 50,
            ),
            NormalizedAttributeCase(
                id = AtaSmartAttributeId.SpinUpTime,
                value = 51,
                worst = 50,
                threshold = 50,
                rawValue = Long.MAX_VALUE,
                expectedStatus = AttributeStatus.CAUTION,
                expectedThreshold = 50,
            ),
            NormalizedAttributeCase(
                id = AtaSmartAttributeId.Dynamic(254, "Vendor_Attribute"),
                value = 100,
                worst = 100,
                threshold = 0,
                rawValue = Long.MAX_VALUE,
                expectedStatus = AttributeStatus.UNKNOWN,
                expectedThreshold = null,
            ),
        )

        cases.forEach { case ->
            val evaluation = DefaultHealthPolicy.evaluate(
                ataSnapshot(
                    attributes = listOf(
                        ataAttribute(
                            id = case.id,
                            value = case.value,
                            worst = case.worst,
                            threshold = case.threshold,
                            rawValue = case.rawValue,
                        ),
                    ),
                ),
            ).evaluations.single { it.ruleKey == case.id.healthRuleKey() }

            assertEquals(case.expectedStatus, evaluation.status, "${case.id.name} status")
            assertEquals(case.value.toLong(), evaluation.value, "${case.id.name} value")
            assertEquals(case.expectedThreshold, evaluation.threshold, "${case.id.name} threshold")
            assertTrue(evaluation.reason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `ATA non-fixed error and lifecycle counters use normalized thresholds`() {
        listOf(
            AtaSmartAttributeId.SpinRetryCount,
            AtaSmartAttributeId.ReportedUncorrect,
            AtaSmartAttributeId.ReallocatedEventCount,
            AtaSmartAttributeId.ProgramFailCount,
        ).forEach { id ->
            val evaluation = DefaultHealthPolicy.evaluate(
                ataSnapshot(
                    attributes = listOf(
                        ataAttribute(id, value = 100, worst = 100, threshold = 50, rawValue = Long.MAX_VALUE),
                    ),
                ),
            ).evaluations.single { it.ruleKey == id.healthRuleKey() }

            assertEquals(AttributeStatus.GOOD, evaluation.status, "${id.name} should not use its raw value")
            assertEquals(50, evaluation.threshold)
        }
    }

    @Test
    fun `ATA evaluates every observed attribute once in ID order`() {
        val attributes = listOf(
            ataAttribute(AtaSmartAttributeId.Dynamic(254, "Vendor_Attribute")),
            ataAttribute(AtaSmartAttributeId.ProgramFailCount),
            ataAttribute(AtaSmartAttributeId.SpinRetryCount),
            ataAttribute(AtaSmartAttributeId.SpinUpTime),
            ataAttribute(AtaSmartAttributeId.RawReadErrorRate),
            ataAttribute(AtaSmartAttributeId.ReallocatedSectorCt),
            ataAttribute(AtaSmartAttributeId.PercentLifetimeRemain),
            ataAttribute(AtaSmartAttributeId.RawReadErrorRate, value = 99),
        )

        val evaluations = DefaultHealthPolicy.evaluate(ataSnapshot(attributes = attributes)).evaluations

        assertEquals(
            listOf(
                "ata.raw_read_error_rate",
                "ata.spin_up_time",
                "ata.reallocated_sector_count",
                "ata.spin_retry_count",
                "ata.program_fail_count",
                "ata.percent_lifetime_remaining",
                "ata.attribute_254",
            ),
            evaluations.map { it.ruleKey },
        )
        assertEquals(evaluations.size, evaluations.map { it.ruleKey }.distinct().size)
    }

    @Test
    fun `policy correction changes only the derived view`() {
        val snapshot = nvmeSnapshot(reportedHealth = DiskHealth.GOOD)
        val originalSnapshot = snapshot.copy()
        val v1 = FixedHealthPolicy(version = 1, health = DiskHealth.BAD)
        val v2 = FixedHealthPolicy(version = 2, health = DiskHealth.CAUTION)

        val v1Result = v1.evaluate(snapshot)
        val v2Result = v2.evaluate(snapshot)

        assertEquals(HealthPolicyMetadata("test", 1), v1.metadata)
        assertEquals(HealthPolicyMetadata("test", 2), v2.metadata)
        assertEquals(DiskHealth.BAD, v1Result.overallHealth)
        assertEquals(DiskHealth.CAUTION, v2Result.overallHealth)
        assertEquals(DiskHealth.GOOD, v1Result.reportedHealth)
        assertEquals(snapshot, originalSnapshot)
    }

    @Test
    fun `policy and evaluation identities reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { HealthPolicyMetadata(" ", 1) }
        assertFailsWith<IllegalArgumentException> { HealthPolicyMetadata("test", 0) }
        assertFailsWith<IllegalArgumentException> {
            FixedHealthPolicy(version = 1, health = DiskHealth.GOOD, name = " ")
        }
    }

    @Test
    fun `rule key can be canonical while key remains a compatibility alias`() {
        val evaluation = AttributeEvaluation(
            key = "legacy.key",
            value = 1,
            status = AttributeStatus.BAD,
            threshold = 0,
            reason = "The value is above the allowed maximum.",
            ruleKey = "protocol.canonical_rule",
        )

        assertEquals("legacy.key", evaluation.key)
        assertEquals("protocol.canonical_rule", evaluation.ruleKey)
    }

    @Test
    fun `rule key is serialized additively and defaults for legacy payloads`() {
        val evaluation = AttributeEvaluation(
            key = "ata.reallocated_sector_count",
            value = 1,
            status = AttributeStatus.BAD,
            threshold = 0,
            reason = "The value is above the allowed maximum.",
        )
        val encoded = Json.encodeToString(evaluation)
        assertTrue(encoded.contains("\"ruleKey\""))

        val decoded = Json.decodeFromString<AttributeEvaluation>(
            """
            {"key":"ata.reallocated_sector_count","value":1,"status":"BAD","threshold":0,"reason":"legacy"}
            """.trimIndent(),
        )
        assertEquals(decoded.key, decoded.ruleKey)
    }

    private fun ataSnapshot(
        lifetimeRemaining: Int? = 95,
        attributes: List<AtaAttribute> = emptyList(),
    ): DiskSnapshot = snapshot(
        metrics = MetricsSnapshot.AtaMetricsSnapshot(
            universal = universal(lifetimeRemaining = lifetimeRemaining),
            attributes = attributes,
        ),
    )

    private fun nvmeSnapshot(
        reportedHealth: DiskHealth = DiskHealth.GOOD,
        percentageUsed: Int? = 5,
        availableSpare: Int? = 100,
        mediaErrors: Long? = 0,
    ): DiskSnapshot = snapshot(
        reportedHealth = reportedHealth,
        metrics = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = universal(),
            percentageUsed = percentageUsed,
            availableSpare = availableSpare,
            mediaErrors = mediaErrors,
            dataUnitsWritten = null,
            dataUnitsRead = null,
        ),
    )

    private fun snapshot(
        reportedHealth: DiskHealth = DiskHealth.GOOD,
        metrics: MetricsSnapshot,
    ) = DiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(1_000),
        deviceKey = "device-key",
        path = "/dev/test",
        model = "Test disk",
        serial = "serial",
        capacityBytes = 1_000,
        temperatureCelsius = null,
        powerOnHours = null,
        health = reportedHealth,
        metricsSnapshot = metrics,
    )

    private fun universal(lifetimeRemaining: Int? = 95) = UniversalMetrics(
        temperatureCelsius = null,
        powerOnHours = null,
        powerCycleCount = null,
        percentageUsed = null,
        lifetimeRemainingPercent = lifetimeRemaining,
        totalBytesWritten = null,
        totalBytesRead = null,
        criticalWarningCount = 0,
    )

    private fun reallocatedAttribute(rawValue: Long) = ataAttribute(
        id = AtaSmartAttributeId.ReallocatedSectorCt,
        rawValue = rawValue,
    )

    private fun ataAttribute(
        id: AtaSmartAttributeId,
        value: Int = 100,
        worst: Int = 100,
        threshold: Int = 50,
        rawValue: Long = 0,
    ) = AtaAttribute(
        id = id,
        name = id.name,
        value = value,
        worst = worst,
        threshold = threshold,
        rawValue = rawValue,
        rawString = rawValue.toString(),
    )

    private data class LifetimeCase(val value: Int, val status: AttributeStatus, val threshold: Long?)
    private data class ThresholdCase(val value: Int?, val status: AttributeStatus, val threshold: Long?)
    private data class ZeroMetricCase(val value: Long?, val status: AttributeStatus)
    private data class NormalizedAttributeCase(
        val id: AtaSmartAttributeId,
        val value: Int,
        val worst: Int,
        val threshold: Int,
        val rawValue: Long,
        val expectedStatus: AttributeStatus,
        val expectedThreshold: Long?,
    )

    private class FixedHealthPolicy(
        version: Int,
        private val health: DiskHealth,
        name: String = "test",
    ) : HealthPolicy(name, version) {
        override fun evaluate(snapshot: DiskSnapshot) = HealthPolicyResult(
            reportedHealth = snapshot.health,
            overallHealth = health,
            evaluations = emptyList(),
        )
    }
}
