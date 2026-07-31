package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.circuit.CircuitBreakerStateStore
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.runtime.facade.DataLoomBuilder
import io.dataloom.runtime.facade.DataLoomCircuitQueueWorkerSchedulerSpec
import io.dataloom.runtime.retry.CircuitBreakerConfiguration
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier
import io.dataloom.runtime.worker.QueueWorkerSchedulingResult

/** External-consumer probe for builder scheduler-circuit configuration and evidence. */
public object BuilderCircuitQueueWorkerSchedulerExternalConsumerProbe {

    public fun configure(
        builder: DataLoomBuilder,
        configuration: CircuitBreakerConfiguration,
        stateStore: CircuitBreakerStateStore,
        scope: CircuitBreakerScope,
        classifier: CircuitBreakerFailureClassifier =
            DefaultCircuitBreakerFailureClassifier,
    ): DataLoomBuilder = builder.circuitQueueWorkerSchedulerConfiguration(
        DataLoomCircuitQueueWorkerSchedulerSpec(
            circuitBreakerConfiguration = configuration,
            circuitBreakerStateStore = stateStore,
            scope = scope,
            failureClassifier = classifier,
        ),
    )

    public fun circuitExecution(
        result: QueueWorkerSchedulingResult,
    ): CircuitBreakerExecutionResult<ScheduleReceipt>? =
        (result as? QueueWorkerSchedulingResult.CircuitProtected)?.executionResult
}
