package com.milkcocoa.info.sapphire.core.snapshot

import com.milkcocoa.info.sapphire.core.api.ResponsePayload
import com.milkcocoa.info.sapphire.core.health.HealthPolicy
import com.milkcocoa.info.sapphire.core.health.HealthPolicyMetadata
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Client-facing projection of an observed [DiskSnapshot].
 *
 * `health`, `evaluations`, and `evaluationPolicy` are derived at the API/output
 * boundary. The observed snapshot remains the ingest and persistence contract.
 */
@Serializable
data class EvaluatedDiskSnapshot(
    /** Collection time inherited from the observed snapshot. */
    val timestamp: Instant,
    /** Stable device identity inherited from the observed snapshot. */
    val deviceKey: String,
    /** Device path at collection time. */
    val path: String,
    /** Device model, if reported. */
    val model: String?,
    /** Device serial number, if reported. */
    val serial: String?,
    /** Nominal capacity in bytes. */
    val capacityBytes: Long,
    /** Temperature in degrees Celsius, if reported. */
    val temperatureCelsius: Int?,
    /** Powered-on time in hours, if reported. */
    val powerOnHours: Long?,
    /** Policy-derived display health; legacy responses may contain only the reported value. */
    val health: DiskHealth,
    /** Raw protocol metrics retained alongside the policy result. */
    val metricsSnapshot: MetricsSnapshot,
    /** Null when decoding a legacy server response that predates evaluated views. */
    val reportedHealth: DiskHealth? = null,
    /** Empty when decoding a legacy server response that predates evaluated views. */
    val evaluations: List<AttributeEvaluation> = emptyList(),
    /** Per-snapshot provenance, including standalone's bare device-latest response. */
    val evaluationPolicy: HealthPolicyMetadata? = null,
) : ResponsePayload

/** Applies [policy] while preserving the observation and recording policy provenance. */
fun DiskSnapshot.toEvaluatedDiskSnapshot(policy: HealthPolicy): EvaluatedDiskSnapshot {
    val result = try {
        policy.evaluate(this)
    } catch (error: java.util.concurrent.CancellationException) {
        // Evaluation is synchronous today, but policies remain an extension boundary.
        // Never turn cancellation into an HTTP/console evaluation failure.
        throw error
    } catch (error: Exception) {
        throw SnapshotEvaluationException(policy.metadata, deviceKey, error)
    }
    return EvaluatedDiskSnapshot(
        timestamp = timestamp,
        deviceKey = deviceKey,
        path = path,
        model = model,
        serial = serial,
        capacityBytes = capacityBytes,
        temperatureCelsius = temperatureCelsius,
        powerOnHours = powerOnHours,
        health = result.overallHealth,
        metricsSnapshot = metricsSnapshot,
        reportedHealth = result.reportedHealth,
        evaluations = result.evaluations,
        evaluationPolicy = policy.metadata,
    )
}

/** Signals that a health policy failed for a specific device observation. */
class SnapshotEvaluationException(
    policy: HealthPolicyMetadata,
    deviceKey: String,
    cause: Exception,
) : IllegalStateException(
    "Health policy ${policy.policyName}/${policy.policyVersion} could not evaluate device $deviceKey.",
    cause,
)
