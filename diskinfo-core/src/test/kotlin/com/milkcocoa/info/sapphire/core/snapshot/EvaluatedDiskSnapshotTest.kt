package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.health.DefaultHealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicyResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class EvaluatedDiskSnapshotTest {
    @Test
    fun `evaluated view preserves raw fields and keeps derived fields separate`() {
        val raw = snapshot(health = DiskHealth.CAUTION)
        val original = raw.copy()

        val evaluated = raw.toEvaluatedDiskSnapshot(DefaultHealthPolicy)

        assertEquals(original, raw)
        assertEquals(raw.timestamp, evaluated.timestamp)
        assertEquals(raw.metricsSnapshot, evaluated.metricsSnapshot)
        assertEquals(DiskHealth.CAUTION, evaluated.reportedHealth)
        assertEquals(DiskHealth.CAUTION, evaluated.health)
        assertEquals(HealthPolicyMetadata("default", 2), evaluated.evaluationPolicy)
        assertEquals(4, evaluated.evaluations.size)
    }

    @Test
    fun `legacy server JSON decodes with missing evaluated additions`() {
        val legacyJson = TestJson.encodeToString(DiskSnapshot.serializer(), snapshot())

        val decoded = TestJson.decodeFromString<EvaluatedDiskSnapshot>(legacyJson)

        assertNull(decoded.reportedHealth)
        assertEquals(emptyList(), decoded.evaluations)
        assertNull(decoded.evaluationPolicy)
    }

    @Test
    fun `evaluation preserves cancellation`() {
        val cancellation = java.util.concurrent.CancellationException("request cancelled")
        val policy = object : HealthPolicy("cancel-test", 1) {
            override fun evaluate(snapshot: DiskSnapshot): HealthPolicyResult = throw cancellation
        }

        val thrown = assertFailsWith<java.util.concurrent.CancellationException> {
            snapshot().toEvaluatedDiskSnapshot(policy)
        }

        assertEquals(cancellation, thrown)
    }

    private fun snapshot(health: DiskHealth = DiskHealth.GOOD) = DiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(1_000),
        deviceKey = "device-key",
        path = "/dev/test",
        model = null,
        serial = null,
        capacityBytes = 1_000,
        temperatureCelsius = null,
        powerOnHours = null,
        health = health,
        metricsSnapshot = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = UniversalMetrics(
                temperatureCelsius = null,
                powerOnHours = null,
                powerCycleCount = null,
                percentageUsed = null,
                lifetimeRemainingPercent = null,
                totalBytesWritten = null,
                totalBytesRead = null,
                criticalWarningCount = null,
            ),
            percentageUsed = null,
            availableSpare = null,
            mediaErrors = null,
            dataUnitsWritten = null,
            dataUnitsRead = null,
        ),
    )
}

private val TestJson = Json { ignoreUnknownKeys = true }
