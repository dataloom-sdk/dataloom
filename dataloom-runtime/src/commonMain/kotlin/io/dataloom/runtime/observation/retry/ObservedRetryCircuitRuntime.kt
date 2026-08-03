package io.dataloom.runtime.observation.retry

import io.dataloom.api.circuit.CircuitAdministrationRequest
import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAdministrationRequest
import io.dataloom.api.time.DataLoomClock
import io.dataloom.runtime.retry.CircuitAdministrationCoordinator
import io.dataloom.runtime.retry.CircuitAdministrationResult
import io.dataloom.runtime.retry.CircuitBreakerExecutionGate
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.RetryAdministrationCoordinator
import io.dataloom.runtime.retry.RetryAdministrationResult
import io.dataloom.runtime.retry.RetryOrchestrationResult
import io.dataloom.runtime.retry.RetryOrchestrationStatus
import io.dataloom.runtime.retry.SynchronizationRetryOrchestrator
import io.dataloom.runtime.retry.SynchronizationRetryRequest

/** Adds bounded telemetry to scheduler-backed retry orchestration. */
public class ObservedSynchronizationRetryOrchestrator(
    private val delegate: SynchronizationRetryOrchestrator,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact orchestration result and isolates telemetry failures. */
    public suspend fun evaluateAndSchedule(
        request: SynchronizationRetryRequest,
    ): RetryOrchestrationResult {
        val result = delegate.evaluateAndSchedule(request)
        observeRetry(request, result)
        return result
    }

    private fun observeRetry(
        request: SynchronizationRetryRequest,
        result: RetryOrchestrationResult,
    ) {
        try {
            telemetry.record(
                RetryCircuitTelemetryEvent(
                    signal = result.status.telemetrySignal(),
                    occurredAt = clock.now(),
                    context = request.synchronizationRequest.telemetryContext(),
                    retryAttempt = request.retryAttempt,
                    selectedDelay = result.selectedDelay,
                    errorCode = result.schedulerError?.code,
                ),
            )
        } catch (_: Exception) {
            // Telemetry must never alter an already-produced retry result.
        }
    }
}

/** Adds bounded telemetry around one circuit execution gate. */
public class ObservedCircuitBreakerExecutionGate(
    private val delegate: CircuitBreakerExecutionGate,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact gate result and invokes [operation] at most once. */
    public suspend fun <T> execute(
        scope: CircuitBreakerScope,
        context: RetryCircuitTelemetryContext = RetryCircuitTelemetryContext(),
        operation: suspend () -> CircuitProtectedOperationResult<T>,
    ): CircuitBreakerExecutionResult<T> {
        val result = delegate.execute(scope, operation)
        observeCircuit(scope, context, result)
        return result
    }

    private fun observeCircuit(
        scope: CircuitBreakerScope,
        context: RetryCircuitTelemetryContext,
        result: CircuitBreakerExecutionResult<*>,
    ) {
        try {
            telemetry.record(
                RetryCircuitTelemetryEvent(
                    signal = result.telemetrySignal(),
                    occurredAt = clock.now(),
                    context = context,
                    scopeKind = scope.kind,
                    circuitPhase = result.recordedPhase(),
                    circuitOperationOutcome = result.operationOutcome(),
                    circuitDetail = result.telemetryDetail(),
                    errorCode = result.errorCode(),
                ),
            )
        } catch (_: Exception) {
            // Telemetry must never alter an already-produced circuit result.
        }
    }
}

/** Adds bounded audit telemetry to retry administration. */
public class ObservedRetryAdministrationCoordinator(
    private val delegate: RetryAdministrationCoordinator,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact command result, including idempotent replay. */
    public suspend fun execute(request: RetryAdministrationRequest): RetryAdministrationResult {
        val result = delegate.execute(request)
        try {
            telemetry.record(
                RetryCircuitTelemetryEvent(
                    signal = result.telemetrySignal(),
                    occurredAt = clock.now(),
                    errorCode = result.errorCode(),
                ),
            )
        } catch (_: Exception) {
            // Durable command history is authoritative; telemetry is isolated.
        }
        return result
    }
}

/** Adds bounded audit telemetry to circuit administration. */
public class ObservedCircuitAdministrationCoordinator(
    private val delegate: CircuitAdministrationCoordinator,
    private val clock: DataLoomClock,
    private val telemetry: BoundedRetryCircuitTelemetry,
) {
    /** Preserves the exact command result, including idempotent replay. */
    public suspend fun execute(request: CircuitAdministrationRequest): CircuitAdministrationResult {
        val result = delegate.execute(request)
        try {
            telemetry.record(
                RetryCircuitTelemetryEvent(
                    signal = result.telemetrySignal(),
                    occurredAt = clock.now(),
                    scopeKind = request.scope.kind,
                    circuitPhase = (result as? CircuitAdministrationResult.Succeeded)
                        ?.record?.state?.resultingRecord?.state?.phase,
                    errorCode = result.errorCode(),
                ),
            )
        } catch (_: Exception) {
            // Durable command history is authoritative; telemetry is isolated.
        }
        return result
    }
}

private fun SynchronizationRequest.telemetryContext(): RetryCircuitTelemetryContext =
    RetryCircuitTelemetryContext(
        workflowId = workflowId,
        tenantId = context.tenantId,
        correlationId = context.correlationId,
        traceId = context.traceId,
    )

private fun RetryOrchestrationStatus.telemetrySignal(): RetryCircuitTelemetrySignal = when (this) {
    RetryOrchestrationStatus.NOT_REQUIRED -> RetryCircuitTelemetrySignal.RETRY_NOT_REQUIRED
    RetryOrchestrationStatus.STOPPED -> RetryCircuitTelemetrySignal.RETRY_STOPPED
    RetryOrchestrationStatus.SCHEDULED -> RetryCircuitTelemetrySignal.RETRY_SCHEDULED
    RetryOrchestrationStatus.SCHEDULER_NOT_CONFIGURED -> {
        RetryCircuitTelemetrySignal.RETRY_SCHEDULER_NOT_CONFIGURED
    }
    RetryOrchestrationStatus.SCHEDULER_FAILED -> RetryCircuitTelemetrySignal.RETRY_SCHEDULER_FAILED
}

private fun CircuitBreakerExecutionResult<*>.telemetrySignal(): RetryCircuitTelemetrySignal = when (this) {
    is CircuitBreakerExecutionResult.Executed -> RetryCircuitTelemetrySignal.CIRCUIT_EXECUTED
    is CircuitBreakerExecutionResult.Rejected -> RetryCircuitTelemetrySignal.CIRCUIT_REJECTED
    is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> {
        RetryCircuitTelemetrySignal.CIRCUIT_PERMISSION_PERSISTENCE_FAILED
    }
    CircuitBreakerExecutionResult.PermissionContentionLimitReached -> {
        RetryCircuitTelemetrySignal.CIRCUIT_PERMISSION_CONTENTION
    }
}

private fun CircuitBreakerExecutionResult<*>.telemetryDetail(): RetryCircuitTelemetryCircuitDetail? =
    when (this) {
        is CircuitBreakerExecutionResult.Executed -> recordResult.telemetryDetail()
        is CircuitBreakerExecutionResult.Rejected -> reason.telemetryDetail()
        is CircuitBreakerExecutionResult.PermissionPersistenceFailure,
        CircuitBreakerExecutionResult.PermissionContentionLimitReached,
        -> null
    }

private fun CircuitBreakerRecordResult.telemetryDetail(): RetryCircuitTelemetryCircuitDetail = when (this) {
    is CircuitBreakerRecordResult.Recorded -> RetryCircuitTelemetryCircuitDetail.STATE_RECORDED
    CircuitBreakerRecordResult.Ignored -> RetryCircuitTelemetryCircuitDetail.STATE_IGNORED
    CircuitBreakerRecordResult.StaleProbe -> RetryCircuitTelemetryCircuitDetail.STALE_PROBE
    is CircuitBreakerRecordResult.ProbeLeaseExpired -> {
        RetryCircuitTelemetryCircuitDetail.PROBE_LEASE_EXPIRED
    }
    is CircuitBreakerRecordResult.ClockRegression -> {
        RetryCircuitTelemetryCircuitDetail.STATE_CLOCK_REGRESSION
    }
    is CircuitBreakerRecordResult.PersistenceFailure -> {
        RetryCircuitTelemetryCircuitDetail.STATE_PERSISTENCE_FAILED
    }
    CircuitBreakerRecordResult.ContentionLimitReached -> RetryCircuitTelemetryCircuitDetail.STATE_CONTENTION
}

private fun CircuitBreakerRejectionReason.telemetryDetail(): RetryCircuitTelemetryCircuitDetail =
    when (this) {
        CircuitBreakerRejectionReason.OPEN -> RetryCircuitTelemetryCircuitDetail.OPEN
        CircuitBreakerRejectionReason.PROBE_IN_FLIGHT -> RetryCircuitTelemetryCircuitDetail.PROBE_IN_FLIGHT
        CircuitBreakerRejectionReason.CLOCK_REGRESSION -> {
            RetryCircuitTelemetryCircuitDetail.PERMISSION_CLOCK_REGRESSION
        }
        CircuitBreakerRejectionReason.PROBE_GENERATION_EXHAUSTED -> {
            RetryCircuitTelemetryCircuitDetail.PROBE_GENERATION_EXHAUSTED
        }
        CircuitBreakerRejectionReason.PROBE_LEASE_DEADLINE_EXHAUSTED -> {
            RetryCircuitTelemetryCircuitDetail.PROBE_LEASE_DEADLINE_EXHAUSTED
        }
    }

private fun CircuitBreakerExecutionResult<*>.recordedPhase() =
    (this as? CircuitBreakerExecutionResult.Executed)
        ?.recordResult
        ?.let { it as? CircuitBreakerRecordResult.Recorded }
        ?.record
        ?.state
        ?.phase

private fun CircuitBreakerExecutionResult<*>.operationOutcome(): RetryCircuitTelemetryOperationOutcome? =
    when (val executed = this as? CircuitBreakerExecutionResult.Executed) {
        null -> null
        else -> when (executed.operationResult) {
            is CircuitProtectedOperationResult.Success -> RetryCircuitTelemetryOperationOutcome.SUCCEEDED
            is CircuitProtectedOperationResult.Failure -> RetryCircuitTelemetryOperationOutcome.CIRCUIT_FAILURE
            is CircuitProtectedOperationResult.NonCircuitFailure -> {
                RetryCircuitTelemetryOperationOutcome.NON_CIRCUIT_FAILURE
            }
        }
    }

private fun CircuitBreakerExecutionResult<*>.errorCode(): ErrorCode? = when (this) {
    is CircuitBreakerExecutionResult.Executed -> when (val operation = operationResult) {
        is CircuitProtectedOperationResult.Success -> (recordResult as? CircuitBreakerRecordResult.PersistenceFailure)
            ?.error?.code
        is CircuitProtectedOperationResult.Failure -> operation.error.code
        is CircuitProtectedOperationResult.NonCircuitFailure -> operation.error.code
    }
    is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> error.code
    is CircuitBreakerExecutionResult.Rejected,
    CircuitBreakerExecutionResult.PermissionContentionLimitReached,
    -> null
}

private fun RetryAdministrationResult.telemetrySignal(): RetryCircuitTelemetrySignal = when (this) {
    is RetryAdministrationResult.Succeeded -> RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_SUCCEEDED
    is RetryAdministrationResult.AuthorizationDenied -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_AUTHORIZATION_DENIED
    }
    is RetryAdministrationResult.PolicyRejected -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_POLICY_REJECTED
    }
    is RetryAdministrationResult.ExecutionRejected -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_EXECUTION_REJECTED
    }
    is RetryAdministrationResult.ExecutionFailed -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_EXECUTION_FAILED
    }
    is RetryAdministrationResult.CommandConflict -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_COMMAND_CONFLICT
    }
    is RetryAdministrationResult.PersistenceFailure -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_PERSISTENCE_FAILED
    }
    is RetryAdministrationResult.ExecutionRecordingUnconfirmed -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_RECORDING_UNCONFIRMED
    }
    is RetryAdministrationResult.ClockRegression -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_CLOCK_REGRESSION
    }
    RetryAdministrationResult.ContentionLimitReached -> {
        RetryCircuitTelemetrySignal.RETRY_ADMINISTRATION_CONTENTION
    }
}

private fun RetryAdministrationResult.errorCode(): ErrorCode? = when (this) {
    is RetryAdministrationResult.PersistenceFailure -> error.code
    is RetryAdministrationResult.ExecutionRecordingUnconfirmed -> persistenceError.code
    is RetryAdministrationResult.ExecutionFailed -> record.state.executionFailure?.code
    else -> null
}

private fun CircuitAdministrationResult.telemetrySignal(): RetryCircuitTelemetrySignal = when (this) {
    is CircuitAdministrationResult.Succeeded -> RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_SUCCEEDED
    is CircuitAdministrationResult.AuthorizationDenied -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_AUTHORIZATION_DENIED
    }
    is CircuitAdministrationResult.PolicyRejected -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_POLICY_REJECTED
    }
    is CircuitAdministrationResult.ExecutionRejected -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_EXECUTION_REJECTED
    }
    is CircuitAdministrationResult.ExecutionFailed -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_EXECUTION_FAILED
    }
    is CircuitAdministrationResult.CommandConflict -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_COMMAND_CONFLICT
    }
    is CircuitAdministrationResult.PersistenceFailure -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_PERSISTENCE_FAILED
    }
    is CircuitAdministrationResult.ExecutionRecordingUnconfirmed -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_RECORDING_UNCONFIRMED
    }
    is CircuitAdministrationResult.ClockRegression -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_CLOCK_REGRESSION
    }
    CircuitAdministrationResult.ContentionLimitReached -> {
        RetryCircuitTelemetrySignal.CIRCUIT_ADMINISTRATION_CONTENTION
    }
}

private fun CircuitAdministrationResult.errorCode(): ErrorCode? = when (this) {
    is CircuitAdministrationResult.PersistenceFailure -> error.code
    is CircuitAdministrationResult.ExecutionRecordingUnconfirmed -> persistenceError.code
    is CircuitAdministrationResult.ExecutionFailed -> record.state.executionFailure?.code
    else -> null
}
