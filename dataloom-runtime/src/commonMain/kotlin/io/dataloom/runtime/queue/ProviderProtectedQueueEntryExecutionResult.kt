package io.dataloom.runtime.queue

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.runtime.execution.protection.ProviderProtectedStrategySynchronizationResult
import io.dataloom.runtime.execution.protection.ProviderProtectionOperationEvidence
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult

/**
 * Exact result of resolving and executing one acquired queue entry through
 * provider timeout and circuit protection.
 *
 * [outcome] is the single requested durable queue transition.
 * [executionResult] preserves the historical synchronization result and
 * [strategyExecutionResult] preserves accepted-strategy replay. At most one is
 * non-null. Both are null when local resolution, correspondence, or deadline
 * enforcement stops before protected execution.
 */
public class ProviderProtectedQueueEntryExecutionResult(
    /** Exact acquired queue entry identity. */
    public val entryId: QueueEntryId,

    /** Requested queue transition derived from the terminal synchronization result. */
    public val outcome: QueueEntryExecutionOutcome,

    /** Historical protected synchronization result. */
    public val executionResult: ProviderProtectedSynchronizationExecutionResult? = null,

    /** Protected immutable accepted-strategy result. */
    public val strategyExecutionResult: ProviderProtectedStrategySynchronizationResult? = null,
) {
    init {
        require(executionResult == null || strategyExecutionResult == null) {
            "A protected queued result cannot contain both legacy and strategy execution."
        }
    }

    /** Defensive provider-operation evidence in execution order. */
    public val operationEvidence: List<ProviderProtectionOperationEvidence> =
        when {
            strategyExecutionResult != null ->
                strategyExecutionResult.operationEvidence.toList()
            executionResult is ProviderProtectedSynchronizationExecutionResult.Executed ->
                executionResult.result.operationEvidence.toList()
            else -> emptyList()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderProtectedQueueEntryExecutionResult) return false
        return entryId == other.entryId &&
            outcome == other.outcome &&
            executionResult == other.executionResult &&
            strategyExecutionResult == other.strategyExecutionResult &&
            operationEvidence == other.operationEvidence
    }

    override fun hashCode(): Int {
        var result = entryId.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + (executionResult?.hashCode() ?: 0)
        result = 31 * result + (strategyExecutionResult?.hashCode() ?: 0)
        result = 31 * result + operationEvidence.hashCode()
        return result
    }

    /** Bounded diagnostic representation that excludes provider values and payloads. */
    override fun toString(): String =
        "ProviderProtectedQueueEntryExecutionResult(" +
            "entryId=${entryId.value}, " +
            "outcome=${outcomeStatus(outcome)}, " +
            "protectedExecutionReached=${executionResult != null || strategyExecutionResult != null}, " +
            "strategyExecution=${strategyExecutionResult != null}, " +
            "operationEvidenceCount=${operationEvidence.size}" +
            ")"
}

private fun outcomeStatus(outcome: QueueEntryExecutionOutcome): String = when (outcome) {
    is QueueEntryExecutionOutcome.Completed -> "COMPLETED"
    is QueueEntryExecutionOutcome.Reschedule -> "RESCHEDULE"
    is QueueEntryExecutionOutcome.Deferred -> "DEFERRED"
    is QueueEntryExecutionOutcome.Failed -> "FAILED"
    is QueueEntryExecutionOutcome.Cancelled -> "CANCELLED"
}
