package io.dataloom.runtime.worker

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.queue.ExpiredLeaseRecoveryResult
import io.dataloom.runtime.queue.QueueCircuitPreExecutionDecision
import io.dataloom.runtime.queue.QueueCircuitProviderFailureDisposition
import io.dataloom.runtime.retry.CircuitBreakerRecordResult

/**
 * Exact expired-lease recovery evidence for one circuit-aware worker cycle.
 *
 * [NotRequested] and [Completed] allow processing to continue. Every other
 * variant terminates the cycle before acquisition or scheduling.
 */
public sealed interface CircuitBreakerQueueWorkerRecoveryResult {

    /** Recovery was disabled for this worker configuration. */
    public data object NotRequested : CircuitBreakerQueueWorkerRecoveryResult

    /** Recovery provider succeeded and circuit recording was accepted. */
    public data class Completed(
        public val result: ExpiredLeaseRecoveryResult,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerQueueWorkerRecoveryResult {
        init {
            require(
                recordResult is CircuitBreakerRecordResult.Recorded ||
                    recordResult is CircuitBreakerRecordResult.Ignored,
            ) {
                "Completed recovery requires an accepted circuit recording result."
            }
        }
    }

    /** Circuit permission stopped recovery before provider invocation. */
    public data class PreExecutionStopped(
        public val decision: QueueCircuitPreExecutionDecision,
    ) : CircuitBreakerQueueWorkerRecoveryResult

    /** Recovery provider ran and returned a canonical failure. */
    public data class ProviderFailure(
        public val error: DataLoomError,
        public val disposition: QueueCircuitProviderFailureDisposition,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerQueueWorkerRecoveryResult

    /** Recovery provider succeeded, but the later circuit recording was not accepted. */
    public data class CircuitRecordingUnconfirmed(
        public val result: ExpiredLeaseRecoveryResult,
        public val recordResult: CircuitBreakerRecordResult,
    ) : CircuitBreakerQueueWorkerRecoveryResult
}

internal fun CircuitBreakerQueueWorkerRecoveryResult.allowsProcessing(): Boolean =
    this is CircuitBreakerQueueWorkerRecoveryResult.NotRequested ||
        this is CircuitBreakerQueueWorkerRecoveryResult.Completed
