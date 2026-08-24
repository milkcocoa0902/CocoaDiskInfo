package com.milkcocoa.info.sapphire.client.api

import com.milkcocoa.info.sapphire.core.api.DeviceState
import com.milkcocoa.info.sapphire.core.api.LatestSnapshotsPayload
import com.milkcocoa.info.sapphire.core.api.NodeApiError
import com.milkcocoa.info.sapphire.core.api.NodeSnapshot
import com.milkcocoa.info.sapphire.core.api.NodeStatus
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import kotlin.time.Instant

internal fun mergeLatestSnapshotPages(
    pages: List<LatestSnapshotsPayload>,
): LatestSnapshotsPayload {
    if (pages.isEmpty()) return LatestSnapshotsPayload(nodes = emptyList())

    val evaluationPolicy = pages.requireConsistentEvaluationPolicy()
    val nodes = linkedMapOf<String, NodeAccumulator>()
    val errors = linkedSetOf<NodeApiError>()
    pages.forEach { page ->
        page.nodes.forEach { node ->
            val existing = nodes[node.nodeId]
            if (existing == null) {
                nodes[node.nodeId] = NodeAccumulator(node)
            } else {
                existing.merge(node)
            }
        }
        errors += page.errors
    }

    return LatestSnapshotsPayload(
        nodes = nodes.values.map(NodeAccumulator::toNodeSnapshot),
        generatedAt = pages.mapNotNull { it.generatedAt }.maxOrNull(),
        partial = pages.any { it.partial },
        errors = errors.toList(),
        errorsTruncated = pages.any { it.errorsTruncated },
        pagination = pages.last().pagination,
        evaluationPolicy = evaluationPolicy,
    )
}

/** Latest pages must describe one evaluated view, even when the response is paged. */
internal fun List<LatestSnapshotsPayload>.requireConsistentEvaluationPolicy(): HealthPolicyMetadata? {
    val policies = buildList {
        this@requireConsistentEvaluationPolicy.forEach { page ->
            add(page.evaluationPolicy)
            page.nodes.forEach { node -> node.devices.forEach { add(it.evaluationPolicy) } }
        }
    }
    if (policies.all { it == null }) return null

    val knownPolicies = policies.filterNotNull().distinct()
    if (policies.any { it == null } || knownPolicies.size != 1) {
        throw LatestSnapshotPolicyMismatchException(
            "Latest snapshot pages have inconsistent evaluation policy metadata. Reload after the server policy stabilizes.",
        )
    }
    return knownPolicies.single()
}

private class NodeAccumulator(node: NodeSnapshot) {
    private var nodeName: String = node.nodeName
    private var status: NodeStatus? = node.status
    private var lastSeenAt: Instant? = node.lastSeenAt
    private val devices = linkedMapOf<String, EvaluatedDiskSnapshot>()
    private val deviceStates = linkedMapOf<String, DeviceState>()
    private val nodeId = node.nodeId

    init {
        node.devices.forEach { devices[it.deviceKey] = it }
        node.deviceStates.forEach { deviceStates[it.deviceKey] = it }
    }

    fun merge(node: NodeSnapshot) {
        nodeName = node.nodeName
        status = node.status ?: status
        lastSeenAt = listOfNotNull(lastSeenAt, node.lastSeenAt).maxOrNull()
        node.devices.forEach { devices[it.deviceKey] = it }
        node.deviceStates.forEach { deviceStates[it.deviceKey] = it }
    }

    fun toNodeSnapshot(): NodeSnapshot = NodeSnapshot(
        nodeId = nodeId,
        nodeName = nodeName,
        devices = devices.values.toList(),
        status = status,
        lastSeenAt = lastSeenAt,
        deviceStates = deviceStates.values.toList(),
    )
}
