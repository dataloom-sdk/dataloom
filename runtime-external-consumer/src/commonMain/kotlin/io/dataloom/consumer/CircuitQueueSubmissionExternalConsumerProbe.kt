package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.submission.CircuitBreakerQueueSubmission
import io.dataloom.runtime.submission.CircuitBreakerQueueSubmissionResult
import io.dataloom.runtime.submission.QueuedSynchronizationWorkEncoder

/** External-consumer compilation probe for circuit-aware queue submission. */
public object CircuitQueueSubmissionExternalConsumerProbe {

    public fun create(
        encoder: QueuedSynchronizationWorkEncoder,
        adapter: CircuitBreakerQueueOperationAdapter,
        scope: CircuitBreakerScope,
    ): CircuitBreakerQueueSubmission = CircuitBreakerQueueSubmission(
        encoder = encoder,
        queueOperationAdapter = adapter,
        scope = scope,
    )

    public fun queueEntryId(
        result: CircuitBreakerQueueSubmissionResult,
    ): QueueEntryId? = when (result) {
        is CircuitBreakerQueueSubmissionResult.EncodingRejected -> null
        is CircuitBreakerQueueSubmissionResult.ContractViolation -> result.queueEntryId
        is CircuitBreakerQueueSubmissionResult.EnqueueEvaluated -> result.queueEntryId
    }

    public fun circuitResult(
        result: CircuitBreakerQueueSubmissionResult,
    ): CircuitBreakerExecutionResult<Unit>? =
        (result as? CircuitBreakerQueueSubmissionResult.EnqueueEvaluated)
            ?.executionResult
}
