package io.dataloom.runtime.queue

import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.runtime.execution.protection.ProviderProtectionOperationEvidence
import io.dataloom.runtime.facade.ProviderProtectedSynchronizationExecutionResult

/**
 * Exact result of resolving and executing one acquired queue entry through
 * provider timeout and circuit protection.
 *
 * [outcome] is the single requested durable queue transition. [executionResult]
 * preserves the exact protected synchronization admission or execution result
 * when the protected facade was reached. A null execution result means local
 * queue work resolution or persisted workflow-deadline enforcement stopped
 * before protected synchronization was invoked.
 */
public class ProviderProtectedQueueEntryExecutionResult(
    /** Exact acquired queue entry identity. */
    public val entryId: QueueEntryId,

    /** Requested queue transition derived from the terminal synchronization result. */
    public val outcome: QueueEntryExecutionOutcome,

    /** Exact protected synchronization result, or null when execution never reached it. */
    public val executionResult: ProviderProtectedSynchronizationExecutionResult? = null,
) {
    /**
     * Defensive provider-operation evidence in execution order.
     *
     * Empty for pre-execution admission rejection and local resolution/deadline
     * failures. Provider values and payloads are not present in this evidence.
     */
    public val operationEvidence: List<ProviderProtectionOperationEvidence> =
        when (val protectedResult = executionResult) {
            is ProviderProtectedSynchronizationExecutionResult.Executed ->
                protectedResult.result.operationEvidence.toList()
            is ProviderProtectedSynchronizationExecutionResult.Rejected,
            null,
            -> emptyList()
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProviderProtectedQueueEntryExecutionResult) return false
        return entryId == other.entryId &&
            outcome == other.outcome &&
            executionResult == other.executionResult &&
            operationEvidence == other.operationEvidence
    }

    override fun hashCode(): Int {
        var result = entryId.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + (executionResult?.hashCode() ?: 0)
        result = 31 * result + operationEvidence.hashCode()
        return result
    }

    /** Bounded diagnostic representation that excludes provider values and payloads. */
    override fun toString(): String =
        "ProviderProtectedQueueEntryExecutionResult(" +
            "entryId=${entryId.value}, " +
            "outcome=${outcomeStatus(outcome)}, " +
            "protectedExecutionReached=${executionResult != null}, " +
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
