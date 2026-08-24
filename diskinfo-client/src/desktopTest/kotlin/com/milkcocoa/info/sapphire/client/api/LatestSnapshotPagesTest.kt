package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.client.presentation.DeviceIdentity
import com.milkcocoa.info.sapphire.client.presentation.FreshnessLevel
import com.milkcocoa.info.sapphire.client.presentation.formatSnapshotAge
import com.milkcocoa.info.sapphire.client.presentation.toPresentation
import com.milkcocoa.info.sapphire.client.presentation.toPresentationState
import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.api.PageMetadata
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.UniversalMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class LatestSnapshotPagesTest {
    @Test
    fun `pages merge devices and states by node and device identity`() {
        val firstPage = LatestSnapshotsPayload(
            nodes = listOf(node("node-a", snapshot("device-a"), receivedState("device-a", 1_000))),
            generatedAt = Instant.fromEpochMilliseconds(1_000),
            pagination = PageMetadata(limit = 1, nextCursor = "cursor-a", hasMore = true),
        )
        val secondPage = LatestSnapshotsPayload(
            nodes = listOf(node("node-a", snapshot("device-b"), receivedState("device-b", 2_000))),
            generatedAt = Instant.fromEpochMilliseconds(2_000),
            partial = true,
            errors = listOf(NodeApiError("node_stale", "Node is stale.", "node-a")),
            pagination = PageMetadata(limit = 1),
        )

        val merged = mergeLatestSnapshotPages(listOf(firstPage, secondPage))

        assertEquals(listOf("device-a", "device-b"), merged.nodes.single().devices.map { it.deviceKey })
        assertEquals(listOf("device-a", "device-b"), merged.nodes.single().deviceStates.map { it.deviceKey })
        assertEquals(Instant.fromEpochMilliseconds(2_000), merged.generatedAt)
        assertTrue(merged.partial)
        assertEquals("node_stale", merged.errors.single().code)
        assertEquals(PageMetadata(limit = 1), merged.pagination)
    }

    @Test
    fun `freshness presentation distinguishes fresh stale and missing metadata`() {
        val receivedAt = Instant.fromEpochMilliseconds(1_000)

        assertEquals(FreshnessLevel.UNKNOWN, null.toPresentation().level)
        assertEquals(
            FreshnessLevel.FRESH,
            DeviceState("device-a", receivedAt, ageMs = 59_000).toPresentation().level,
        )
        assertEquals(
            FreshnessLevel.STALE,
            DeviceState("device-a", receivedAt, ageMs = 60_000, stale = true).toPresentation().level,
        )
        assertEquals("59s ago", formatSnapshotAge(59_000))
        assertEquals("1m ago", formatSnapshotAge(60_000))
    }

    @Test
    fun `old payload presents each device with unknown freshness`() {
        val payload = LatestSnapshotsPayload(
            nodes = listOf(
                NodeSnapshot(
                    nodeId = "node-a",
                    nodeName = "Node A",
                    devices = listOf(snapshot("device-a")),
                ),
            ),
        )

        assertEquals(
            FreshnessLevel.UNKNOWN,
            payload.toPresentationState()
                .freshnessByDevice
                .getValue(DeviceIdentity("node-a", "device-a"))
                .level,
        )
    }

    @Test
    fun `pages retain a shared evaluated policy`() {
        val policy = HealthPolicyMetadata("default", 1)
        val merged = mergeLatestSnapshotPages(
            listOf(
                LatestSnapshotsPayload(
                    nodes = listOf(node("node-a", snapshot("device-a", policy), receivedState("device-a", 1))),
                    evaluationPolicy = policy,
                ),
                LatestSnapshotsPayload(
                    nodes = listOf(node("node-a", snapshot("device-b", policy), receivedState("device-b", 2))),
                    evaluationPolicy = policy,
                ),
            ),
        )

        assertEquals(policy, merged.evaluationPolicy)
        assertTrue(merged.nodes.single().devices.all { it.evaluationPolicy == policy })
    }

    @Test
    fun `pages reject mixed evaluation policies`() {
        val firstPolicy = HealthPolicyMetadata("default", 1)
        val secondPolicy = HealthPolicyMetadata("default", 2)

        val error = kotlin.test.assertFailsWith<LatestSnapshotPolicyMismatchException> {
            mergeLatestSnapshotPages(
                listOf(
                    LatestSnapshotsPayload(
                        nodes = listOf(node("node-a", snapshot("device-a", firstPolicy), receivedState("device-a", 1))),
                        evaluationPolicy = firstPolicy,
                    ),
                    LatestSnapshotsPayload(
                        nodes = listOf(node("node-a", snapshot("device-b", secondPolicy), receivedState("device-b", 2))),
                        evaluationPolicy = secondPolicy,
                    ),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("inconsistent evaluation policy"))
    }

    private fun node(
        nodeId: String,
        snapshot: EvaluatedDiskSnapshot,
        state: DeviceState,
    ): NodeSnapshot = NodeSnapshot(
        nodeId = nodeId,
        nodeName = "Node A",
        devices = listOf(snapshot),
        status = NodeStatus.ACTIVE,
        lastSeenAt = state.lastReceivedAt,
        deviceStates = listOf(state),
    )

    private fun receivedState(
        deviceKey: String,
        receivedAtMillis: Long,
    ): DeviceState = DeviceState(
        deviceKey = deviceKey,
        lastReceivedAt = Instant.fromEpochMilliseconds(receivedAtMillis),
        ageMs = 0,
    )

    private fun snapshot(
        deviceKey: String,
        evaluationPolicy: HealthPolicyMetadata? = null,
    ): EvaluatedDiskSnapshot = EvaluatedDiskSnapshot(
        timestamp = Instant.fromEpochMilliseconds(0),
        deviceKey = deviceKey,
        path = "/dev/$deviceKey",
        model = null,
        serial = null,
        capacityBytes = 1,
        temperatureCelsius = null,
        powerOnHours = null,
        health = DiskHealth.UNKNOWN,
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
        evaluationPolicy = evaluationPolicy,
    )
}
