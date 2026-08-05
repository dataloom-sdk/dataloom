package io.dataloom.runtime.execution.protection

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.strategy.StrategyCacheAccessProvider
import io.dataloom.api.strategy.StrategyCacheAccessRequest
import io.dataloom.api.strategy.StrategyCacheAccessResult
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.RetryTimeoutKind
import io.dataloom.runtime.retry.StrategyCacheAccessCircuitOperation
import io.dataloom.runtime.retry.StrategyCacheAccessTimeoutErrors

/**
 * Cache-access bridge that preserves the protected storage surface and adds one
 * independently governed cache-verification operation.
 */
internal class ProviderProtectionStrategyCacheAccessBridge(
    private val storageBridge: ProviderProtectionStorageBridge,
    private val delegate: StrategyCacheAccessProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    private val scope: CircuitBreakerScope,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
    private val timeoutCoordinator: RetryTimeoutCoordinator?,
) : StrategyCacheAccessProvider {
    init {
        require(storageBridge.descriptor.id == delegate.descriptor.id) {
            "Strategy cache-access bridge storage provider must match the delegate."
        }
        require(scope.providerId == null || scope.providerId == delegate.descriptor.id) {
            "Strategy cache-access circuit scope provider must match storage."
        }
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
        ) {
            "Strategy cache-access scope operation must match " +
                StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS
                    .retryOperation.value + "."
        }
    }

    override val descriptor: ProviderDescriptor
        get() = storageBridge.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = storageBridge.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        storageBridge.health()

    override suspend fun close(): ProviderOperationResult<Unit> =
        storageBridge.close()

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        storageBridge.readOutboundChanges(request)

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = storageBridge.applyInboundChanges(request)

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = storageBridge.acknowledgeOutboundChanges(request)

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        storageBridge.readCheckpoint(request)

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = storageBridge.writeCheckpoint(request)

    override suspend fun evaluateCacheAccess(
        request: StrategyCacheAccessRequest,
    ): ProviderOperationResult<StrategyCacheAccessResult> =
        adaptCacheAccessProtectionResult(
            providerId = descriptor.id,
            operation =
                StrategyCacheAccessCircuitOperation.EVALUATE_CACHE_ACCESS.retryOperation,
            result = providerOperationAdapter.execute(scope) {
                executeWithOptionalTimeout(request)
            },
            evidenceCollector = evidenceCollector,
        )

    private suspend fun executeWithOptionalTimeout(
        request: StrategyCacheAccessRequest,
    ): ProviderOperationResult<StrategyCacheAccessResult> {
        val coordinator = timeoutCoordinator
            ?: return delegate.evaluateCacheAccess(request)
        return when (
            val result = coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                operation = { delegate.evaluateCacheAccess(request) },
            )
        ) {
            is RetryTimeoutExecutionResult.Completed -> result.value
            is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
                StrategyCacheAccessTimeoutErrors.providerTimedOut(),
            )
            is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
                ProviderOperationResult.Failure(
                    StrategyCacheAccessTimeoutErrors.workflowDeadlineExceeded(),
                )
            is RetryTimeoutExecutionResult.ClockRegression ->
                ProviderOperationResult.Failure(
                    StrategyCacheAccessTimeoutErrors.clockRegression(),
                )
        }
    }
}

private fun <T> adaptCacheAccessProtectionResult(
    providerId: ProviderId,
    operation: RetryOperation,
    result: CircuitBreakerExecutionResult<T>,
    evidenceCollector: ProviderProtectionEvidenceCollector,
): ProviderOperationResult<T> = when (result) {
    is CircuitBreakerExecutionResult.Executed -> {
        val invocation = when (result.operationResult) {
            is CircuitProtectedOperationResult.Success ->
                ProviderProtectionInvocation.SUCCEEDED
            is CircuitProtectedOperationResult.Failure ->
                ProviderProtectionInvocation.CIRCUIT_FAILURE
            is CircuitProtectedOperationResult.NonCircuitFailure ->
                ProviderProtectionInvocation.NON_CIRCUIT_FAILURE
        }
        val operationError = when (val operationResult = result.operationResult) {
            is CircuitProtectedOperationResult.Success -> null
            is CircuitProtectedOperationResult.Failure -> operationResult.error
            is CircuitProtectedOperationResult.NonCircuitFailure -> operationResult.error
        }
        evidenceCollector.add(
            ProviderProtectionOperationEvidence(
                providerId = providerId,
                operation = operation,
                invocation = invocation,
                error = operationError,
                recordResult = result.recordResult,
            ),
        )

        if (!cacheAccessRecordingAccepted(result.recordResult)) {
            ProviderOperationResult.Failure(
                CacheAccessProviderProtectionErrors.recordingUnconfirmed(operation),
            )
        } else {
            when (val operationResult = result.operationResult) {
                is CircuitProtectedOperationResult.Success ->
                    ProviderOperationResult.Success(operationResult.value)
                is CircuitProtectedOperationResult.Failure ->
                    ProviderOperationResult.Failure(operationResult.error)
                is CircuitProtectedOperationResult.NonCircuitFailure ->
                    ProviderOperationResult.Failure(operationResult.error)
            }
        }
    }
    is CircuitBreakerExecutionResult.Rejected -> {
        evidenceCollector.add(
            ProviderProtectionOperationEvidence(
                providerId = providerId,
                operation = operation,
                invocation = ProviderProtectionInvocation.NOT_EXECUTED,
                preExecutionReason = ProviderProtectionPreExecutionReason.CIRCUIT_REJECTED,
                rejectionReason = result.reason,
                retryAt = result.retryAt,
            ),
        )
        ProviderOperationResult.Failure(
            CacheAccessProviderProtectionErrors.circuitRejected(
                operation,
                result.reason,
            ),
        )
    }
    is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> {
        evidenceCollector.add(
            ProviderProtectionOperationEvidence(
                providerId = providerId,
                operation = operation,
                invocation = ProviderProtectionInvocation.NOT_EXECUTED,
                preExecutionReason =
                    ProviderProtectionPreExecutionReason.PERMISSION_PERSISTENCE_FAILURE,
                error = result.error,
            ),
        )
        ProviderOperationResult.Failure(result.error)
    }
    CircuitBreakerExecutionResult.PermissionContentionLimitReached -> {
        evidenceCollector.add(
            ProviderProtectionOperationEvidence(
                providerId = providerId,
                operation = operation,
                invocation = ProviderProtectionInvocation.NOT_EXECUTED,
                preExecutionReason =
                    ProviderProtectionPreExecutionReason.PERMISSION_CONTENTION_LIMIT_REACHED,
            ),
        )
        ProviderOperationResult.Failure(
            CacheAccessProviderProtectionErrors.permissionContention(operation),
        )
    }
}

private fun cacheAccessRecordingAccepted(result: CircuitBreakerRecordResult): Boolean =
    result is CircuitBreakerRecordResult.Recorded || result is CircuitBreakerRecordResult.Ignored

private object CacheAccessProviderProtectionErrors {
    fun circuitRejected(
        operation: RetryOperation,
        reason: CircuitBreakerRejectionReason,
    ): DataLoomError = Error(
        code = ErrorCode("PROVIDER_CIRCUIT_${reason.name}"),
        category = ErrorCategory.PROVIDER,
        recoverability = when (reason) {
            CircuitBreakerRejectionReason.OPEN,
            CircuitBreakerRejectionReason.PROBE_IN_FLIGHT,
            -> Recoverability.RECOVERABLE
            CircuitBreakerRejectionReason.CLOCK_REGRESSION,
            CircuitBreakerRejectionReason.PROBE_GENERATION_EXHAUSTED,
            CircuitBreakerRejectionReason.PROBE_LEASE_DEADLINE_EXHAUSTED,
            -> Recoverability.NON_RECOVERABLE
        },
        message = "Circuit permission rejected ${operation.value} before provider execution.",
    )

    fun permissionContention(operation: RetryOperation): DataLoomError = Error(
        code = ErrorCode("PROVIDER_CIRCUIT_PERMISSION_CONTENTION"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.RECOVERABLE,
        message = "Circuit permission contention prevented ${operation.value} execution.",
    )

    fun recordingUnconfirmed(operation: RetryOperation): DataLoomError = Error(
        code = ErrorCode("PROVIDER_CIRCUIT_RECORDING_UNCONFIRMED"),
        category = ErrorCategory.STATE,
        recoverability = Recoverability.UNKNOWN,
        message = "Provider operation ${operation.value} executed, but circuit recording was not confirmed.",
    )

    private data class Error(
        override val code: ErrorCode,
        override val category: ErrorCategory,
        override val recoverability: Recoverability,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val message: String,
        override val cause: Throwable? = null,
    ) : DataLoomError
}
