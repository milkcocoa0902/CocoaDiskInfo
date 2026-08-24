package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.AttributeStatus
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ApiResponseCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `new model reads Phase 4 payload without metadata`() {
        val payload = json.decodeFromString<LatestSnapshotsPayload>(
            """{"nodes":[{"nodeId":"node-a","nodeName":"Node A","devices":[]}]}""",
        )

        assertNull(payload.generatedAt)
        assertFalse(payload.partial)
        assertEquals(emptyList(), payload.errors)
        assertNull(payload.pagination)
        assertNull(payload.nodes.single().status)
        assertEquals(emptyList(), payload.nodes.single().deviceStates)
    }

    @Test
    fun `Phase 4 compatible reader ignores additive metadata`() {
        val encoded = json.encodeToString(
            LatestSnapshotsPayload(
                nodes = listOf(
                    NodeSnapshot(
                        nodeId = "node-a",
                        nodeName = "Node A",
                        devices = emptyList(),
                    ),
                ),
                partial = true,
            ),
        )

        val oldPayload = json.decodeFromString<Phase4LatestSnapshotsPayload>(encoded)

        assertEquals("node-a", oldPayload.nodes.single().nodeId)
    }

    @Test
    fun `evaluated snapshot decodes a legacy snapshot payload`() {
        val encoded = json.encodeToString(legacySnapshot())

        val decoded = json.decodeFromString<EvaluatedDiskSnapshot>(encoded)

        assertEquals(DiskHealth.GOOD, decoded.health)
        assertNull(decoded.reportedHealth)
        assertEquals(emptyList(), decoded.evaluations)
        assertNull(decoded.evaluationPolicy)
    }

    @Test
    fun `new payload retains server evaluation metadata`() {
        val policy = HealthPolicyMetadata("default", 1)
        val payload = LatestSnapshotsPayload(
            nodes = listOf(
                NodeSnapshot(
                    nodeId = "node-a",
                    nodeName = "Node A",
                    devices = listOf(
                        legacySnapshot().copy(
                            health = DiskHealth.CAUTION,
                            reportedHealth = DiskHealth.GOOD,
                            evaluations = listOf(
                                AttributeEvaluation(
                                    key = "nvme.media_errors",
                                    value = 1,
                                    status = AttributeStatus.CAUTION,
                                    threshold = 0,
                                    reason = "Media errors reported.",
                                ),
                            ),
                            evaluationPolicy = policy,
                        ),
                    ),
                ),
            ),
            evaluationPolicy = policy,
        )

        val decoded = json.decodeFromString<LatestSnapshotsPayload>(json.encodeToString(payload))

        assertEquals(policy, decoded.evaluationPolicy)
        assertEquals(DiskHealth.GOOD, decoded.nodes.single().devices.single().reportedHealth)
        assertTrue(decoded.nodes.single().devices.single().evaluations.isNotEmpty())
    }

    private fun legacySnapshot() = EvaluatedDiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(0),
        deviceKey = "device-a",
        path = "/dev/device-a",
        model = null,
        serial = null,
        capacityBytes = 1,
        temperatureCelsius = null,
        powerOnHours = null,
        health = DiskHealth.GOOD,
        metricsSnapshot = MetricsSnapshot.NvmeMetricsSnapshot(
            universal = UniversalMetrics(
                temperatureCelsius = null,
                powerOnHours = null,
                powerCycleCount = null,
                percentageUsed = null,
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

@Serializable
private data class Phase4LatestSnapshotsPayload(
    val nodes: List<Phase4NodeSnapshot>,
)

@Serializable
private data class Phase4NodeSnapshot(
    val nodeId: String,
    val nodeName: String,
)
