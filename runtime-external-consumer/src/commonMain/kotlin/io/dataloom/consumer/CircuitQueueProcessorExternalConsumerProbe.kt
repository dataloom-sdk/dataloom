package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingResult
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter

/** External-consumer compilation probe for circuit-aware bounded queue processing. */
public object CircuitQueueProcessorExternalConsumerProbe {

    public fun create(
        adapter: CircuitBreakerQueueOperationAdapter,
        handler: QueueEntryExecutionHandler,
        acquisition: CircuitBreakerScope,
        completion: CircuitBreakerScope,
        reschedule: CircuitBreakerScope,
        deferral: CircuitBreakerScope,
        failure: CircuitBreakerScope,
        cancellation: CircuitBreakerScope,
    ): CircuitBreakerDurableQueueExecutionProcessor =
        CircuitBreakerDurableQueueExecutionProcessor(
            queueOperationAdapter = adapter,
            executionHandler = handler,
            scopes = QueueProcessingCircuitScopes(
                acquisition = acquisition,
                completion = completion,
                reschedule = reschedule,
                deferral = deferral,
                failure = failure,
                cancellation = cancellation,
            ),
        )

    public fun terminalEntry(
        result: CircuitBreakerQueueProcessingResult,
    ): QueueEntryId? = when (result) {
        is CircuitBreakerQueueProcessingResult.NoWork -> null
        is CircuitBreakerQueueProcessingResult.Processed -> null
        is CircuitBreakerQueueProcessingResult.PreExecutionStopped -> result.affectedEntryId
        is CircuitBreakerQueueProcessingResult.ProviderFailure -> result.affectedEntryId
        is CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed ->
            result.affectedEntryIds.firstOrNull()
        is CircuitBreakerQueueProcessingResult.QueueContractViolation -> null
    }
}
