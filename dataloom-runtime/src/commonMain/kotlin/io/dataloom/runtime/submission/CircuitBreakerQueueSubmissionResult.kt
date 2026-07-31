package io.dataloom.runtime.submission

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult

/**
 * Result of one circuit-aware queue submission.
 *
 * Local preflight failures are represented directly because no circuit
 * permission or queue-provider operation occurred. [EnqueueEvaluated] preserves
 * the complete circuit result, including whether enqueue ran and whether the
 * later circuit-state recording succeeded.
 */
public sealed interface CircuitBreakerQueueSubmissionResult {

    /** The application-owned encoder rejected the submission before circuit access. */
    public data class EncodingRejected(
        public val error: DataLoomError,
    ) : CircuitBreakerQueueSubmissionResult

    /** The encoded enqueue request failed local correspondence validation. */
    public data class ContractViolation(
        public val error: DataLoomError,
        public val queueEntryId: QueueEntryId,
    ) : CircuitBreakerQueueSubmissionResult

    /**
     * Local preflight succeeded and the enqueue circuit was evaluated.
     *
     * [executionResult] distinguishes pre-execution rejection from an operation
     * that ran exactly once. When execution occurred, it also preserves the
     * complete post-execution circuit-recording result.
     */
    public data class EnqueueEvaluated(
        public val queueEntryId: QueueEntryId,
        public val executionResult: CircuitBreakerExecutionResult<Unit>,
    ) : CircuitBreakerQueueSubmissionResult
}
