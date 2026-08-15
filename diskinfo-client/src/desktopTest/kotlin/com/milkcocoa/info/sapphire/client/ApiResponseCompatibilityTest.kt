package com.milkcocoa.info.sapphire.client

import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

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
