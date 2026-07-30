package io.dataloom.consumer

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.scheduling.ScheduleReceipt
import io.dataloom.api.scheduling.ScheduleRequest
import io.dataloom.api.scheduling.SchedulerProvider
import io.dataloom.runtime.retry.CircuitBreakerCoordinator
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerFailureClassifier
import io.dataloom.runtime.retry.CircuitBreakerFailureDisposition
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRetrySchedulingAdapter
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.DefaultCircuitBreakerFailureClassifier

/** Compile-only use of circuit permission, provider, and retry scheduling adapters. */
internal suspend fun compileCircuitExecutionGateConsumer(
    coordinator: CircuitBreakerCoordinator,
    schedulerProvider: SchedulerProvider,
    scope: CircuitBreakerScope,
    scheduleRequest: ScheduleRequest,
    error: DataLoomError,
): CircuitBreakerExecutionResult<ScheduleReceipt> {
    val gate = CircuitBreakerExecutionGate(coordinator)
    val success: CircuitProtectedOperationResult<String> =
        CircuitProtectedOperationResult.Success("ok")
    val failure: CircuitProtectedOperationResult<Nothing> =
        CircuitProtectedOperationResult.Failure(error)
    val nonCircuitFailure: CircuitProtectedOperationResult<Nothing> =
        CircuitProtectedOperationResult.NonCircuitFailure(error)
    val directResult: CircuitBreakerExecutionResult<String> =
        gate.execute(scope) { success }
    val classifier = CircuitBreakerFailureClassifier {
        CircuitBreakerFailureDisposition.RECORD_FAILURE
    }
    val providerAdapter = CircuitBreakerProviderOperationAdapter(
        executionGate = gate,
        failureClassifier = classifier,
    )
    val schedulingAdapter = CircuitBreakerRetrySchedulingAdapter(
        schedulerProvider = schedulerProvider,
        providerOperationAdapter = providerAdapter,
        scope = scope,
    )

    directResult.toString()
    failure.toString()
    nonCircuitFailure.toString()
    DefaultCircuitBreakerFailureClassifier.classify(error).toString()
    return schedulingAdapter.schedule(scheduleRequest)
}
