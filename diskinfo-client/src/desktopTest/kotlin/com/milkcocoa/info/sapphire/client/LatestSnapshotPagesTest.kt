package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.api.PageMetadata
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
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

    private fun node(
        nodeId: String,
        snapshot: DiskSnapshot,
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

    private fun snapshot(deviceKey: String): DiskSnapshot = DiskSnapshot(
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
    )
}
