package io.dataloom.runtime.strategy

import io.dataloom.runtime.execution.protection.ProviderProtectionOperationEvidence

/**
 * Exact result of one strategy admission/execution through protected storage
 * and transport provider boundaries.
 *
 * [strategyResult] is the existing strategy result without reinterpretation.
 * [operationEvidence] is a defensive, ordered snapshot of provider permission,
 * invocation, canonical failure, and post-execution circuit-recording evidence.
 * Provider return values, payloads, credentials, headers, checkpoint content,
 * exception text, and arbitrary metadata are not included.
 */
public class ProviderProtectedStrategySynchronizationResult(
    /** Exact existing strategy admission or execution result. */
    public val strategyResult: StrategySynchronizationExecutionResult,

    operationEvidence: List<ProviderProtectionOperationEvidence>,
) {
    /** Defensive immutable evidence snapshot in provider invocation order. */
    public val operationEvidence: List<ProviderProtectionOperationEvidence> =
        operationEvidence.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderProtectedStrategySynchronizationResult) return false
        return strategyResult == other.strategyResult &&
            operationEvidence == other.operationEvidence
    }

    override fun hashCode(): Int =
        31 * strategyResult.hashCode() + operationEvidence.hashCode()

    /** Bounded diagnostics that do not render outputs, payloads, or provider values. */
    override fun toString(): String =
        "ProviderProtectedStrategySynchronizationResult(" +
            "status=${strategyStatus(strategyResult)}, " +
            "operationEvidenceCount=${operationEvidence.size}" +
            ")"
}

private fun strategyStatus(result: StrategySynchronizationExecutionResult): String = when (result) {
    is StrategySynchronizationExecutionResult.Executed -> "EXECUTED"
    is StrategySynchronizationExecutionResult.Failed -> "FAILED"
    is StrategySynchronizationExecutionResult.FallbackActivated -> "FALLBACK_ACTIVATED"
    is StrategySynchronizationExecutionResult.FallbackUnavailable -> "FALLBACK_UNAVAILABLE"
    is StrategySynchronizationExecutionResult.Cancelled -> "CANCELLED"
    is StrategySynchronizationExecutionResult.Deferred -> "DEFERRED"
    is StrategySynchronizationExecutionResult.Rejected -> "REJECTED"
}
