package io.dataloom.runtime.execution.protection

import io.dataloom.api.circuit.CircuitBreakerScope
import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderDescriptor
import io.dataloom.api.provider.ProviderHealth
import io.dataloom.api.provider.ProviderId
import io.dataloom.api.provider.ProviderInitializationContext
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.storage.InboundChangeApplyRequest
import io.dataloom.api.storage.LocalConflictCandidateReadRequest
import io.dataloom.api.storage.LocalConflictCandidateReadResult
import io.dataloom.api.storage.OutboundChangeReadRequest
import io.dataloom.api.storage.OutboundChangeReadResult
import io.dataloom.api.storage.StorageProvider
import io.dataloom.api.synchronization.ChangeSetAcknowledgement
import io.dataloom.api.synchronization.CheckpointReadRequest
import io.dataloom.api.synchronization.CheckpointWriteRequest
import io.dataloom.api.synchronization.OutboundChangeAcknowledgementRequest
import io.dataloom.api.synchronization.SynchronizationCheckpoint
import io.dataloom.api.strategy.StrategyLocalFallbackProvider
import io.dataloom.api.strategy.StrategyLocalFallbackRequest
import io.dataloom.api.strategy.StrategyLocalFallbackResult
import io.dataloom.api.strategy.StrategyReconciliationProvider
import io.dataloom.api.strategy.StrategyReconciliationRequest
import io.dataloom.api.strategy.StrategyReconciliationResult
import io.dataloom.api.transport.PullChangesRequest
import io.dataloom.api.transport.PullChangesResult
import io.dataloom.api.transport.PushChangesRequest
import io.dataloom.api.transport.TransportProvider
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerProviderOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitBreakerRejectionReason
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.ProtectedStorageOperations
import io.dataloom.runtime.retry.ProtectedTransportOperations
import io.dataloom.runtime.retry.RetryTimeoutCoordinator
import io.dataloom.runtime.retry.RetryTimeoutExecutionResult
import io.dataloom.runtime.retry.RetryTimeoutKind
import io.dataloom.runtime.retry.StrategyLocalFallbackCircuitOperation
import io.dataloom.runtime.retry.StrategyLocalFallbackTimeoutErrors
import io.dataloom.runtime.retry.StrategyReconciliationCircuitOperation
import io.dataloom.runtime.retry.StrategyReconciliationTimeoutErrors
import io.dataloom.runtime.retry.StorageCircuitOperation
import io.dataloom.runtime.retry.TransportCircuitOperation

internal class ProviderProtectionEvidenceCollector {
    private val mutableEvidence = mutableListOf<ProviderProtectionOperationEvidence>()

    fun add(evidence: ProviderProtectionOperationEvidence) {
        mutableEvidence += evidence
    }

    fun snapshot(): List<ProviderProtectionOperationEvidence> = mutableEvidence.toList()
}

/**
 * Pipeline-local bridge that preserves full protected-operation evidence in a
 * separate collector before adapting the terminal operation outcome to the
 * historical [StorageProvider] contract.
 *
 * [readLocalConflictCandidate][StorageProvider.readLocalConflictCandidate] is
 * forwarded like every other operation, circuit-protected through
 * [ProtectedStorageOperations.readLocalConflictCandidate] via the dedicated
 * [io.dataloom.runtime.retry.StorageCircuitScopes.readLocalConflictCandidate]
 * scope. A wrapped provider's real `Found`/`NotFound` result passes through
 * unchanged when the circuit is closed; a tripped circuit rejects the call
 * the same way it rejects every other protected storage operation. See
 * [io.dataloom.api.storage.StorageProvider]'s own KDoc for
 * `readLocalConflictCandidate`'s narrow, opt-in purpose.
 */
internal class ProviderProtectionStorageBridge(
    private val protectedOperations: ProtectedStorageOperations,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
) : StorageProvider {
    override val descriptor: ProviderDescriptor
        get() = protectedOperations.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = execute(StorageCircuitOperation.INITIALIZE) {
        protectedOperations.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(StorageCircuitOperation.HEALTH) {
            protectedOperations.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.CLOSE) {
            protectedOperations.close()
        }

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        execute(StorageCircuitOperation.READ_OUTBOUND_CHANGES) {
            protectedOperations.readOutboundChanges(request)
        }

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.APPLY_INBOUND_CHANGES) {
            protectedOperations.applyInboundChanges(request)
        }

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.ACKNOWLEDGE_OUTBOUND_CHANGES) {
            protectedOperations.acknowledgeOutboundChanges(request)
        }

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        execute(StorageCircuitOperation.READ_CHECKPOINT) {
            protectedOperations.readCheckpoint(request)
        }

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> =
        execute(StorageCircuitOperation.WRITE_CHECKPOINT) {
            protectedOperations.writeCheckpoint(request)
        }

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> =
        execute(StorageCircuitOperation.READ_LOCAL_CONFLICT_CANDIDATE) {
            protectedOperations.readLocalConflictCandidate(request)
        }

    private suspend fun <T> execute(
        operation: StorageCircuitOperation,
        block: suspend () -> CircuitBreakerExecutionResult<T>,
    ): ProviderOperationResult<T> = adaptProviderProtectionResult(
        providerId = descriptor.id,
        operation = operation.retryOperation,
        result = block(),
        evidenceCollector = evidenceCollector,
    )
}

/** Pipeline-local transport bridge with the same evidence-preserving rules. */
internal class ProviderProtectionTransportBridge(
    private val protectedOperations: ProtectedTransportOperations,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
) : TransportProvider {
    override val descriptor: ProviderDescriptor
        get() = protectedOperations.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = execute(TransportCircuitOperation.INITIALIZE) {
        protectedOperations.initialize(context)
    }

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        execute(TransportCircuitOperation.HEALTH) {
            protectedOperations.health()
        }

    override suspend fun close(): ProviderOperationResult<Unit> =
        execute(TransportCircuitOperation.CLOSE) {
            protectedOperations.close()
        }

    override suspend fun pushChanges(
        request: PushChangesRequest,
    ): ProviderOperationResult<ChangeSetAcknowledgement> =
        execute(TransportCircuitOperation.PUSH_CHANGES) {
            protectedOperations.pushChanges(request)
        }

    override suspend fun pullChanges(
        request: PullChangesRequest,
    ): ProviderOperationResult<PullChangesResult> =
        execute(TransportCircuitOperation.PULL_CHANGES) {
            protectedOperations.pullChanges(request)
        }

    private suspend fun <T> execute(
        operation: TransportCircuitOperation,
        block: suspend () -> CircuitBreakerExecutionResult<T>,
    ): ProviderOperationResult<T> = adaptProviderProtectionResult(
        providerId = descriptor.id,
        operation = operation.retryOperation,
        result = block(),
        evidenceCollector = evidenceCollector,
    )
}


/**
 * Strategy-local-fallback bridge that preserves the protected storage surface
 * and adds one independently governed fallback operation.
 */
internal class ProviderProtectionStrategyFallbackBridge(
    private val storageBridge: ProviderProtectionStorageBridge,
    private val delegate: StrategyLocalFallbackProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    private val scope: CircuitBreakerScope,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
    private val timeoutCoordinator: RetryTimeoutCoordinator?,
) : StrategyLocalFallbackProvider {
    init {
        require(storageBridge.descriptor.id == delegate.descriptor.id) {
            "Strategy fallback bridge storage provider must match the fallback provider."
        }
        require(scope.providerId == null || scope.providerId == delegate.descriptor.id) {
            "Strategy fallback circuit scope provider must match the storage provider."
        }
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
        ) {
            "Strategy fallback circuit scope operation must match " +
                "${StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation.value}."
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

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> =
        storageBridge.readLocalConflictCandidate(request)

    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> =
        adaptProviderProtectionResult(
            providerId = descriptor.id,
            operation =
                StrategyLocalFallbackCircuitOperation.EVALUATE_LOCAL_FALLBACK.retryOperation,
            result = providerOperationAdapter.execute(scope) {
                executeWithOptionalTimeout(request)
            },
            evidenceCollector = evidenceCollector,
        )

    private suspend fun executeWithOptionalTimeout(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> {
        val coordinator = timeoutCoordinator
            ?: return delegate.evaluateLocalFallback(request)
        return when (
            val result = coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                operation = { delegate.evaluateLocalFallback(request) },
            )
        ) {
            is RetryTimeoutExecutionResult.Completed -> result.value
            is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
                StrategyLocalFallbackTimeoutErrors.providerTimedOut(),
            )
            is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
                ProviderOperationResult.Failure(
                    StrategyLocalFallbackTimeoutErrors.workflowDeadlineExceeded(),
                )
            is RetryTimeoutExecutionResult.ClockRegression ->
                ProviderOperationResult.Failure(
                    StrategyLocalFallbackTimeoutErrors.clockRegression(),
                )
        }
    }
}

/**
 * Strategy-reconciliation bridge that preserves the protected storage surface
 * and adds one independently governed reconciliation operation.
 */
internal class ProviderProtectionStrategyReconciliationBridge(
    private val storageBridge: ProviderProtectionStorageBridge,
    private val delegate: StrategyReconciliationProvider,
    private val providerOperationAdapter: CircuitBreakerProviderOperationAdapter,
    private val scope: CircuitBreakerScope,
    private val evidenceCollector: ProviderProtectionEvidenceCollector,
    private val timeoutCoordinator: RetryTimeoutCoordinator?,
) : StrategyReconciliationProvider {
    init {
        require(storageBridge.descriptor.id == delegate.descriptor.id) {
            "Strategy reconciliation bridge storage provider must match the delegate."
        }
        require(scope.providerId == null || scope.providerId == delegate.descriptor.id) {
            "Strategy reconciliation circuit scope provider must match storage."
        }
        require(
            scope.operation == null ||
                scope.operation ==
                StrategyReconciliationCircuitOperation.RECONCILE.retryOperation,
        ) {
            "Strategy reconciliation scope operation must match " +
                StrategyReconciliationCircuitOperation.RECONCILE.retryOperation.value + "."
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

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> =
        storageBridge.readLocalConflictCandidate(request)

    override suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> =
        adaptProviderProtectionResult(
            providerId = descriptor.id,
            operation = StrategyReconciliationCircuitOperation.RECONCILE.retryOperation,
            result = providerOperationAdapter.execute(scope) {
                executeWithOptionalTimeout(request)
            },
            evidenceCollector = evidenceCollector,
        )

    private suspend fun executeWithOptionalTimeout(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> {
        val coordinator = timeoutCoordinator
            ?: return delegate.reconcileStrategy(request)
        return when (
            val result = coordinator.execute(
                kind = RetryTimeoutKind.PROVIDER,
                operation = { delegate.reconcileStrategy(request) },
            )
        ) {
            is RetryTimeoutExecutionResult.Completed -> result.value
            is RetryTimeoutExecutionResult.TimedOut -> ProviderOperationResult.Failure(
                StrategyReconciliationTimeoutErrors.providerTimedOut(),
            )
            is RetryTimeoutExecutionResult.WorkflowDeadlineExceeded ->
                ProviderOperationResult.Failure(
                    StrategyReconciliationTimeoutErrors.workflowDeadlineExceeded(),
                )
            is RetryTimeoutExecutionResult.ClockRegression ->
                ProviderOperationResult.Failure(
                    StrategyReconciliationTimeoutErrors.clockRegression(),
                )
        }
    }
}

/** Storage bridge preserving both optional strategy storage capabilities. */
internal class ProviderProtectionStrategyFallbackAndReconciliationBridge(
    private val fallbackBridge: ProviderProtectionStrategyFallbackBridge,
    private val reconciliationBridge: ProviderProtectionStrategyReconciliationBridge,
) : StrategyLocalFallbackProvider, StrategyReconciliationProvider {
    init {
        require(fallbackBridge.descriptor.id == reconciliationBridge.descriptor.id) {
            "Combined strategy storage bridges must wrap the same provider."
        }
    }

    override val descriptor: ProviderDescriptor
        get() = fallbackBridge.descriptor

    override suspend fun initialize(
        context: ProviderInitializationContext,
    ): ProviderOperationResult<Unit> = fallbackBridge.initialize(context)

    override suspend fun health(): ProviderOperationResult<ProviderHealth> =
        fallbackBridge.health()

    override suspend fun close(): ProviderOperationResult<Unit> = fallbackBridge.close()

    override suspend fun readOutboundChanges(
        request: OutboundChangeReadRequest,
    ): ProviderOperationResult<OutboundChangeReadResult> =
        fallbackBridge.readOutboundChanges(request)

    override suspend fun applyInboundChanges(
        request: InboundChangeApplyRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.applyInboundChanges(request)

    override suspend fun acknowledgeOutboundChanges(
        request: OutboundChangeAcknowledgementRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.acknowledgeOutboundChanges(request)

    override suspend fun readCheckpoint(
        request: CheckpointReadRequest,
    ): ProviderOperationResult<SynchronizationCheckpoint?> =
        fallbackBridge.readCheckpoint(request)

    override suspend fun writeCheckpoint(
        request: CheckpointWriteRequest,
    ): ProviderOperationResult<Unit> = fallbackBridge.writeCheckpoint(request)

    override suspend fun readLocalConflictCandidate(
        request: LocalConflictCandidateReadRequest,
    ): ProviderOperationResult<LocalConflictCandidateReadResult> =
        fallbackBridge.readLocalConflictCandidate(request)

    override suspend fun evaluateLocalFallback(
        request: StrategyLocalFallbackRequest,
    ): ProviderOperationResult<StrategyLocalFallbackResult> =
        fallbackBridge.evaluateLocalFallback(request)

    override suspend fun reconcileStrategy(
        request: StrategyReconciliationRequest,
    ): ProviderOperationResult<StrategyReconciliationResult> =
        reconciliationBridge.reconcileStrategy(request)
}

private fun <T> adaptProviderProtectionResult(
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

        if (!recordingAccepted(result.recordResult)) {
            ProviderOperationResult.Failure(
                ProviderProtectionErrors.recordingUnconfirmed(operation),
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
            ProviderProtectionErrors.circuitRejected(operation, result.reason),
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
            ProviderProtectionErrors.permissionContention(operation),
        )
    }
}

private fun recordingAccepted(result: CircuitBreakerRecordResult): Boolean =
    result is CircuitBreakerRecordResult.Recorded || result is CircuitBreakerRecordResult.Ignored

private object ProviderProtectionErrors {
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
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
