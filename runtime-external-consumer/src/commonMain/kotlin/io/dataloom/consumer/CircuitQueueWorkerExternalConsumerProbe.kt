package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.queue.QueueEntryExecutionHandler
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerCoordinator
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRecoveryResult
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRuntime
import io.dataloom.runtime.worker.QueueWorkerConfiguration
import io.dataloom.runtime.worker.QueueWorkerRunRequest

/** External-consumer compile probe for the circuit-aware queue-worker surface. */
public object CircuitQueueWorkerExternalConsumerProbe {

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
    ): CircuitBreakerQueueWorkerCoordinator = CircuitBreakerQueueWorkerRuntime.create(
        queueProvider = queueProvider,
        executionGate = executionGate,
        recoveryScope = recoveryScope,
        processingScopes = processingScopes,
        executionHandler = executionHandler,
        schedulerProvider = schedulerProvider,
        clock = clock,
        configuration = configuration,
        failureClassifier = failureClassifier,
    )

    public suspend fun run(
        coordinator: CircuitBreakerQueueWorkerCoordinator,
        request: QueueWorkerRunRequest,
    ): CircuitBreakerQueueWorkerRunResult = coordinator.run(request)

    public fun preserveRecoveryEvidence(
        result: CircuitBreakerQueueWorkerRecoveryResult,
    ): CircuitBreakerQueueWorkerRecoveryResult = result
}
