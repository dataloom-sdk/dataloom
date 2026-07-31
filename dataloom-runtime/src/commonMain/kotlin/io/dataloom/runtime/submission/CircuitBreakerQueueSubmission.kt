package io.dataloom.runtime.submission

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.QueueCircuitOperation

/**
 * Submits one encoded queue entry through explicit circuit permission and
 * outcome recording.
 *
 * ## Ordering
 *
 * Encoding and structural validation complete before circuit permission is
 * requested. This ordering prevents invalid local input from reserving a
 * half-open probe or touching the circuit-state store.
 *
 * After preflight succeeds, [queueOperationAdapter] evaluates [scope] and
 * invokes queue enqueue at most once. The returned result never collapses an
 * executed enqueue and a later circuit-recording failure into one plain provider
 * failure.
 *
 * ## Scope
 *
 * Provider-bearing scopes must identify the adapter's queue provider.
 * Operation-bearing scopes must identify `queue.enqueue`. Global and workflow
 * scopes remain valid explicit choices. No fallback or inference is applied.
 *
 * ## Cancellation
 *
 * Caller cancellation and unexpected encoder, circuit-store, or provider
 * exceptions propagate unchanged.
 */
public class CircuitBreakerQueueSubmission(
    encoder: QueuedSynchronizationWorkEncoder,
    private val queueOperationAdapter: CircuitBreakerQueueOperationAdapter,
    public val scope: CircuitBreakerScope,
) {

    private val preflight = QueueSubmissionPreflight(encoder)

    init {
        require(
            scope.providerId == null ||
                scope.providerId == queueOperationAdapter.descriptor.id,
        ) {
            "CircuitBreakerQueueSubmission scope provider must match the queue provider."
        }
        require(
            scope.operation == null ||
                scope.operation == QueueCircuitOperation.ENQUEUE.retryOperation,
        ) {
            "CircuitBreakerQueueSubmission scope operation must be queue.enqueue."
        }
    }

    /**
     * Runs local preflight, then evaluates the enqueue circuit and invokes the
     * provider at most once when permission is granted.
     */
    public suspend fun submit(
        submission: QueuedSynchronizationSubmission,
    ): CircuitBreakerQueueSubmissionResult = when (val prepared = preflight.prepare(submission)) {
        is QueueSubmissionPreflightResult.EncodingRejected -> {
            CircuitBreakerQueueSubmissionResult.EncodingRejected(prepared.error)
        }
        is QueueSubmissionPreflightResult.ContractViolation -> {
            CircuitBreakerQueueSubmissionResult.ContractViolation(
                error = prepared.error,
                queueEntryId = prepared.queueEntryId,
            )
        }
        is QueueSubmissionPreflightResult.Ready -> {
            CircuitBreakerQueueSubmissionResult.EnqueueEvaluated(
                queueEntryId = submission.queueEntryId,
                executionResult = queueOperationAdapter.enqueue(scope, prepared.request),
            )
        }
    }
}
