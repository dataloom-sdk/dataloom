package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier

/**
 * Production assembly for one circuit-aware queue-worker coordinator.
 *
 * One queue-operation adapter is shared by expired-lease recovery and bounded
 * acquisition/transitions. Construction performs no provider call, state-store
 * access, clock read, scheduling, queue mutation, or coroutine launch.
 *
 * A timeout-enforcing queue-provider may be supplied to compose provider timeout
 * and circuit policy without losing durable ambiguity evidence.
 */
public object CircuitBreakerQueueWorkerRuntime {

    /** Creates a circuit-aware worker with one shared queue adapter and processor. */
    public fun create(
        queueProvider: QueueProvider,
        executionGate: CircuitBreakerExecutionGate,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider?,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
    ): CircuitBreakerQueueWorkerCoordinator {
        val adapter = CircuitBreakerQueueOperationAdapter(
            queueProvider = queueProvider,
            executionGate = executionGate,
            failureClassifier = failureClassifier,
        )
        val processor = CircuitBreakerDurableQueueExecutionProcessor(
            queueOperationAdapter = adapter,
            executionHandler = executionHandler,
            scopes = processingScopes,
        )
        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = adapter,
            recoveryScope = recoveryScope,
            queueProcessor = processor,
            schedulerProvider = schedulerProvider,
            clock = clock,
            configuration = configuration,
        )
    }
}
