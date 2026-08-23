package io.dataloom.runtime.worker

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.CircuitBreakerDurableQueueExecutionProcessor
import io.dataloom.runtime.queue.CircuitBreakerQueueProcessingEngine
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueEntryTransitionObserver
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier
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
 *
 * @param transitionObserver optional [QueueEntryTransitionObserver] passed
 *   straight through to the assembled
 *   [CircuitBreakerDurableQueueExecutionProcessor] -- see that class's own
 *   KDoc on its own `transitionObserver` parameter for the exact contract.
 *   Defaults to `null`, reproducing byte-for-byte the behavior this runtime
 *   had before this parameter existed.
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
        transitionObserver: QueueEntryTransitionObserver? = null,
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
            transitionObserver = transitionObserver,
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

    /**
     * Creates a circuit-aware worker with separately governed queue and
     * scheduler circuit boundaries.
     *
     * The optional scheduler timeout from [configuration] is applied before
     * scheduler circuit adaptation. A timeout is therefore classified as a
     * scheduler dependency failure, while an accepted schedule remains visible
     * even when its later circuit-state update is not accepted.
     */
    public fun createWithSchedulerCircuit(
        queueProvider: QueueProvider,
        queueExecutionGate: CircuitBreakerExecutionGate,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        executionHandler: QueueEntryExecutionHandler,
        schedulerProvider: SchedulerProvider,
        schedulerExecutionGate: CircuitBreakerExecutionGate,
        schedulerScope: CircuitBreakerScope,
        clock: DataLoomClock,
        configuration: QueueWorkerConfiguration,
        queueFailureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
        schedulerFailureClassifier: CircuitBreakerFailureClassifier =
            DefaultCircuitBreakerFailureClassifier,
        transitionObserver: QueueEntryTransitionObserver? = null,
    ): CircuitBreakerQueueWorkerCoordinator {
        val queueAdapter = CircuitBreakerQueueOperationAdapter(
            queueProvider = queueProvider,
            executionGate = queueExecutionGate,
            failureClassifier = queueFailureClassifier,
        )
        val processor = CircuitBreakerDurableQueueExecutionProcessor(
            queueOperationAdapter = queueAdapter,
            executionHandler = executionHandler,
            scopes = processingScopes,
            transitionObserver = transitionObserver,
        )
        val protectedScheduler = checkNotNull(
            assembleQueueWorkerSchedulerProvider(
                provider = schedulerProvider,
                timeout = configuration.schedulerProviderTimeout,
                clock = clock,
            ),
        )
        val schedulerAdapter = CircuitBreakerRetrySchedulingAdapter(
            schedulerProvider = protectedScheduler,
            providerOperationAdapter = CircuitBreakerProviderOperationAdapter(
                executionGate = schedulerExecutionGate,
                failureClassifier = schedulerFailureClassifier,
            ),
            scope = schedulerScope,
        )
        return CircuitBreakerQueueWorkerCoordinator(
            queueOperationAdapter = queueAdapter,
            recoveryScope = recoveryScope,
            queueProcessor = CircuitBreakerQueueProcessingEngine { request ->
                processor.process(request)
            },
            schedulerProvider = null,
            schedulerCircuitAdapter = schedulerAdapter,
            clock = clock,
            configuration = configuration,
        )
    }
}
