package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.QueueCircuitOperation

/** Classification of a canonical queue-provider failure at the circuit boundary. */
public enum class QueueCircuitProviderFailureDisposition {
    /** The failure contributes to circuit health. */
    CIRCUIT_FAILURE,

    /** The dependency responded; the failure is semantic rather than availability-related. */
    NON_CIRCUIT_FAILURE,
}

/** Exact reason an operation stopped before the queue provider was invoked. */
public sealed interface QueueCircuitPreExecutionDecision {

    /** The selected circuit rejected the operation. */
    public data class Rejected(
        public val reason: CircuitBreakerRejectionReason,
        public val retryAt: DataLoomInstant? = null,
    ) : QueueCircuitPreExecutionDecision

    /** Circuit-state loading failed before provider invocation. */
    public data class PermissionPersistenceFailure(
        public val error: DataLoomError,
    ) : QueueCircuitPreExecutionDecision

    /** Atomic circuit-state contention exceeded the configured permission budget. */
    public data object PermissionContentionLimitReached : QueueCircuitPreExecutionDecision
}

/**
 * Successful provider operation plus its accepted circuit-recording evidence.
 *
 * Only [CircuitBreakerRecordResult.Recorded] and
 * [CircuitBreakerRecordResult.Ignored] are accepted in this value. Every other
 * recording result terminates processing through [CircuitRecordingUnconfirmed].
 */
public data class QueueCircuitOperationRecord(
    public val operation: QueueCircuitOperation,
    public val affectedEntryId: QueueEntryId? = null,
    public val recordResult: CircuitBreakerRecordResult,
) {
    init {
        require(
            recordResult is CircuitBreakerRecordResult.Recorded ||
                recordResult is CircuitBreakerRecordResult.Ignored,
        ) {
            "QueueCircuitOperationRecord requires an accepted circuit recording result."
        }
    }
}

/**
 * Terminal result of one circuit-aware bounded queue-processing cycle.
 *
 * Every variant distinguishes whether a provider operation ran. A successful
 * provider transition is counted before an unconfirmed later circuit-state
 * write is reported. Failed or pre-execution transitions are never counted as
 * durably confirmed.
 */
public sealed interface CircuitBreakerQueueProcessingResult {

    /** Acquisition completed successfully and returned no entries. */
    public data class NoWork(
        public val acquisitionRecord: QueueCircuitOperationRecord,
    ) : CircuitBreakerQueueProcessingResult

    /** All acquired entries completed one confirmed provider transition. */
    public class Processed(
        public val summary: QueueProcessingSummary,
        public val acquisitionLimitReached: Boolean = false,
        public val earliestRescheduledAt: DataLoomInstant? = null,
        public val earliestDeferredAt: DataLoomInstant? = null,
        operationRecords: List<QueueCircuitOperationRecord>,
    ) : CircuitBreakerQueueProcessingResult {

        /** Defensive immutable snapshot in provider execution order. */
        public val operationRecords: List<QueueCircuitOperationRecord> = operationRecords.toList()

        public val earliestNextAvailableAt: DataLoomInstant?
            get() {
                val retryAt = earliestRescheduledAt
                val deferredAt = earliestDeferredAt
                return when {
                    retryAt == null -> deferredAt
                    deferredAt == null -> retryAt
                    retryAt.epochMilliseconds <= deferredAt.epochMilliseconds -> retryAt
                    else -> deferredAt
                }
            }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Processed) return false
            return summary == other.summary &&
                acquisitionLimitReached == other.acquisitionLimitReached &&
                earliestRescheduledAt == other.earliestRescheduledAt &&
                earliestDeferredAt == other.earliestDeferredAt &&
                operationRecords == other.operationRecords
        }

        override fun hashCode(): Int {
            var result = summary.hashCode()
            result = 31 * result + acquisitionLimitReached.hashCode()
            result = 31 * result + (earliestRescheduledAt?.hashCode() ?: 0)
            result = 31 * result + (earliestDeferredAt?.hashCode() ?: 0)
            result = 31 * result + operationRecords.hashCode()
            return result
        }

        override fun toString(): String =
            "Processed(summary=$summary, acquisitionLimitReached=$acquisitionLimitReached, " +
                "earliestRescheduledAt=$earliestRescheduledAt, " +
                "earliestDeferredAt=$earliestDeferredAt, operationRecords=$operationRecords)"
    }

    /** Circuit permission stopped an operation before provider invocation. */
    public data class PreExecutionStopped(
        public val operation: QueueCircuitOperation,
        public val stage: QueueProcessingFailureStage,
        public val decision: QueueCircuitPreExecutionDecision,
        public val summary: QueueProcessingSummary,
        public val affectedEntryId: QueueEntryId? = null,
        public val leaseId: QueueLeaseId? = null,
    ) : CircuitBreakerQueueProcessingResult

    /** The provider ran and returned a canonical failure. */
    public data class ProviderFailure(
        public val operation: QueueCircuitOperation,
        public val stage: QueueProcessingFailureStage,
        public val error: DataLoomError,
        public val disposition: QueueCircuitProviderFailureDisposition,
        public val recordResult: CircuitBreakerRecordResult,
        public val summary: QueueProcessingSummary,
        public val affectedEntryId: QueueEntryId? = null,
        public val leaseId: QueueLeaseId? = null,
    ) : CircuitBreakerQueueProcessingResult

    /**
     * The provider operation succeeded, but its later circuit recording was not
     * accepted.
     *
     * [summary] already includes the successful provider operation. Processing
     * stops and later entries are not executed. For acquisition, [affectedEntryIds]
     * identifies entries whose leases were confirmed by the provider.
     */
    public class CircuitRecordingUnconfirmed(
        public val operation: QueueCircuitOperation,
        public val stage: QueueProcessingFailureStage,
        public val recordResult: CircuitBreakerRecordResult,
        public val summary: QueueProcessingSummary,
        affectedEntryIds: List<QueueEntryId> = emptyList(),
        public val leaseId: QueueLeaseId? = null,
    ) : CircuitBreakerQueueProcessingResult {

        /** Defensive immutable snapshot of entries affected by the successful provider call. */
        public val affectedEntryIds: List<QueueEntryId> = affectedEntryIds.toList()

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CircuitRecordingUnconfirmed) return false
            return operation == other.operation &&
                stage == other.stage &&
                recordResult == other.recordResult &&
                summary == other.summary &&
                affectedEntryIds == other.affectedEntryIds &&
                leaseId == other.leaseId
        }

        override fun hashCode(): Int {
            var result = operation.hashCode()
            result = 31 * result + stage.hashCode()
            result = 31 * result + recordResult.hashCode()
            result = 31 * result + summary.hashCode()
            result = 31 * result + affectedEntryIds.hashCode()
            result = 31 * result + (leaseId?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "CircuitRecordingUnconfirmed(operation=$operation, stage=$stage, " +
                "recordResult=$recordResult, summary=$summary, " +
                "affectedEntryIds=$affectedEntryIds, leaseId=$leaseId)"
    }

    /** Acquisition succeeded but returned structurally invalid entries. */
    public data class QueueContractViolation(
        public val error: DataLoomError,
        public val summary: QueueProcessingSummary,
        public val acquisitionRecord: QueueCircuitOperationRecord,
        public val leaseId: QueueLeaseId? = null,
    ) : CircuitBreakerQueueProcessingResult
}
