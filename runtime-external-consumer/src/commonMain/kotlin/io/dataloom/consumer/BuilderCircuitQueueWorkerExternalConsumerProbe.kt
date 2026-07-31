package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.runtime.facade.DataLoom
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomCircuitQueueWorkerSpec
import io.dataloom.runtime.facade.DataLoomQueueWorkerSpec
import io.dataloom.runtime.queue.QueueProcessingCircuitScopes
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.QueueCircuitBreakerFailureClassifier
import io.dataloom.runtime.worker.CircuitBreakerQueueWorkerRunResult
import io.dataloom.runtime.worker.QueueWorkerRunRequest

/** External-consumer compile probe for DataLoomBuilder circuit-worker adoption. */
public object BuilderCircuitQueueWorkerExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        workerSpec: DataLoomQueueWorkerSpec,
        circuitConfiguration: CircuitBreakerConfiguration,
        stateStore: CircuitBreakerStateStore,
        recoveryScope: CircuitBreakerScope,
        processingScopes: QueueProcessingCircuitScopes,
        failureClassifier: CircuitBreakerFailureClassifier =
            QueueCircuitBreakerFailureClassifier,
    ): DataLoomBuilder = builder.circuitQueueWorkerConfiguration(
        DataLoomCircuitQueueWorkerSpec(
            workerSpec = workerSpec,
            circuitBreakerConfiguration = circuitConfiguration,
            circuitBreakerStateStore = stateStore,
            recoveryScope = recoveryScope,
            processingScopes = processingScopes,
            failureClassifier = failureClassifier,
        ),
    )

    public suspend fun run(
        dataLoom: DataLoom,
        request: QueueWorkerRunRequest,
    ): CircuitBreakerQueueWorkerRunResult =
        requireNotNull(dataLoom.circuitQueueWorker).run(request)
}
