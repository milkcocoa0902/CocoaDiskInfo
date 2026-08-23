package com.milkcocoa.info.sapphire.agent.sink

import com.milkcocoa.info.colotok.core.logger.Colotok
import com.milkcocoa.info.sapphire.agent.usecase.SnapshotUseCase
import com.milkcocoa.info.sapphire.core.health.DefaultHealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import com.milkcocoa.info.sapphire.core.snapshot.AttributeEvaluation
import com.milkcocoa.info.sapphire.core.snapshot.DiskHealth
import com.milkcocoa.info.sapphire.core.snapshot.DiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.EvaluatedDiskSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.MetricsSnapshot
import com.milkcocoa.info.sapphire.core.snapshot.toEvaluatedDiskSnapshot
import com.milkcocoa.info.colotok.core.formatter.details.LogStructure
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

interface SnapshotSink {
    suspend fun write(snapshot: DiskSnapshot)
}

class ColotokSnapshotSink(
    private val healthPolicy: HealthPolicy = DefaultHealthPolicy,
) : SnapshotSink {
    override suspend fun write(snapshot: DiskSnapshot) {
        Colotok.info(snapshot.toEvaluatedDiskSnapshot(healthPolicy).toConsoleLogStructure())
    }
}

/**
 * Console-only projection.  Repository and remote sinks continue to receive the
 * original [DiskSnapshot], while this serializable structure exposes the active
 * policy and derived evaluations in text and structured output.
 */
@Serializable
private data class EvaluatedSnapshotConsoleLog(
    val timestamp: Instant,
    val deviceKey: String,
    val path: String,
    val model: String?,
    val serial: String?,
    val capacityBytes: Long,
    val temperatureCelsius: Int?,
    val powerOnHours: Long?,
    val health: DiskHealth,
    val metricsSnapshot: MetricsSnapshot,
    val reportedHealth: DiskHealth,
    val evaluations: List<AttributeEvaluation>,
    val evaluationPolicy: com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata,
) : LogStructure {
    override fun stringify(): String {
        val observed = DiskSnapshot(
            timestamp = timestamp,
            deviceKey = deviceKey,
            path = path,
            model = model,
            serial = serial,
            capacityBytes = capacityBytes,
            temperatureCelsius = temperatureCelsius,
            powerOnHours = powerOnHours,
            health = health,
            metricsSnapshot = metricsSnapshot,
        )
        return buildString {
            append(observed.stringify())
            appendLine()
            appendLine()
            appendLine("Evaluation")
            appendLine("- policy: ${evaluationPolicy.policyName}/${evaluationPolicy.policyVersion}")
            appendLine("- reported health: ${reportedHealth.name}")
            evaluations.forEach { evaluation ->
                appendLine(
                    "- ${evaluation.ruleKey}: ${evaluation.status.name} " +
                        "(value=${evaluation.value ?: "-"}, threshold=${evaluation.threshold ?: "-"}, " +
                        "reason=${evaluation.reason})",
                )
            }
        }.trimEnd()
    }
}

private fun EvaluatedDiskSnapshot.toConsoleLogStructure(): EvaluatedSnapshotConsoleLog =
    EvaluatedSnapshotConsoleLog(
        timestamp = timestamp,
        deviceKey = deviceKey,
        path = path,
        model = model,
        serial = serial,
        capacityBytes = capacityBytes,
        temperatureCelsius = temperatureCelsius,
        powerOnHours = powerOnHours,
        health = health,
        metricsSnapshot = metricsSnapshot,
        reportedHealth = checkNotNull(reportedHealth) {
            "Health policy evaluation must provide reported health for console output."
        },
        evaluations = evaluations,
        evaluationPolicy = checkNotNull(evaluationPolicy) {
            "Health policy evaluation must provide policy metadata for console output."
        },
    )

class RepositorySnapshotSink(
    private val snapshotUseCase: SnapshotUseCase,
) : SnapshotSink {
    override suspend fun write(snapshot: DiskSnapshot) {
        try {
            snapshotUseCase.saveSnapshot(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Colotok.warn(
                msg = "Failed to persist disk snapshot.",
                attr = mapOf(
                    "device_key" to snapshot.deviceKey,
                    "device_path" to snapshot.path,
                    "error" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
        }
    }
}

class CompositeSnapshotSink(
    private val sinks: List<SnapshotSink>,
) : SnapshotSink {
    constructor(vararg sinks: SnapshotSink) : this(sinks.toList())

    override suspend fun write(snapshot: DiskSnapshot) {
        sinks.forEach { it.write(snapshot) }
    }
}
