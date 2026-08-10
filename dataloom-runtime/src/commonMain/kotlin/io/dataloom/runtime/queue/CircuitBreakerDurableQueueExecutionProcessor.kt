package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.retry.CircuitBreakerExecutionResult
import io.dataloom.runtime.retry.CircuitBreakerQueueOperationAdapter
import io.dataloom.runtime.retry.CircuitBreakerRecordResult
import io.dataloom.runtime.retry.CircuitProtectedOperationResult
import io.dataloom.runtime.retry.QueueCircuitOperation

/**
 * Executes one bounded queue-processing cycle through explicit circuit
 * permission and outcome recording.
 *
 * ## Evidence model
 *
 * Acquisition and every durable transition retain two independent facts:
 *
 * 1. whether the queue-provider operation ran and what it returned; and
 * 2. whether the subsequent circuit-state recording was accepted.
 *
 * A provider success is counted as confirmed before an unconfirmed later
 * circuit write is reported. A pre-execution rejection or provider failure is
 * never counted as a successful transition. Processing stops at the first
 * non-normal circuit or provider outcome, and later entries are not executed.
 *
 * ## Scope model
 *
 * [scopes] supplies an explicit scope for acquisition and every transition.
 * All scopes are validated during construction before state-store or provider
 * access. No scope inheritance, fallback, provider inference, operation
 * inference, tenant inference, or workflow inference is performed.
 *
 * ## Cancellation
 *
 * Caller cancellation and unexpected handler, provider, or circuit-store
 * exceptions propagate unchanged.
 */
public class CircuitBreakerDurableQueueExecutionProcessor(
    private val queueOperationAdapter: CircuitBreakerQueueOperationAdapter,
    private val executionHandler: QueueEntryExecutionHandler,
    private val scopes: QueueProcessingCircuitScopes,
) {

    init {
        validateScope(scopes.acquisition, QueueCircuitOperation.ACQUIRE)
        validateScope(scopes.completion, QueueCircuitOperation.COMPLETE)
        validateScope(scopes.reschedule, QueueCircuitOperation.RESCHEDULE)
        validateScope(scopes.deferral, QueueCircuitOperation.DEFER)
        validateScope(scopes.failure, QueueCircuitOperation.FAIL)
        validateScope(scopes.cancellation, QueueCircuitOperation.CANCEL)
    }

    /** Executes one acquisition and at most one handler/transition per acquired entry. */
    public suspend fun process(
        request: QueueProcessingRequest,
    ): CircuitBreakerQueueProcessingResult {
        val operationRecords = mutableListOf<QueueCircuitOperationRecord>()

        val acquisitionExecution = queueOperationAdapter.acquire(
            scope = scopes.acquisition,
            request = request.acquireRequest,
        )

        val preExecutionDecision = acquisitionExecution.toPreExecutionDecision()
        if (preExecutionDecision != null) {
            return CircuitBreakerQueueProcessingResult.PreExecutionStopped(
                operation = QueueCircuitOperation.ACQUIRE,
                stage = QueueProcessingFailureStage.ACQUISITION,
                decision = preExecutionDecision,
                summary = zeroSummary(),
            )
        }

        val acquisitionExecuted =
            acquisitionExecution as CircuitBreakerExecutionResult.Executed<QueueAcquireResult>
        val acquireResult = when (val operation = acquisitionExecuted.operationResult) {
            is CircuitProtectedOperationResult.Success -> operation.value
            is CircuitProtectedOperationResult.Failure -> {
                return CircuitBreakerQueueProcessingResult.ProviderFailure(
                    operation = QueueCircuitOperation.ACQUIRE,
                    stage = QueueProcessingFailureStage.ACQUISITION,
                    error = operation.error,
                    disposition = QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE,
                    recordResult = acquisitionExecuted.recordResult,
                    summary = zeroSummary(),
                )
            }
            is CircuitProtectedOperationResult.NonCircuitFailure -> {
                return CircuitBreakerQueueProcessingResult.ProviderFailure(
                    operation = QueueCircuitOperation.ACQUIRE,
                    stage = QueueProcessingFailureStage.ACQUISITION,
                    error = operation.error,
                    disposition = QueueCircuitProviderFailureDisposition.NON_CIRCUIT_FAILURE,
                    recordResult = acquisitionExecuted.recordResult,
                    summary = zeroSummary(),
                )
            }
        }

        val acquisitionRecord = acceptedRecord(
            operation = QueueCircuitOperation.ACQUIRE,
            affectedEntryId = null,
            recordResult = acquisitionExecuted.recordResult,
        )
        if (acquisitionRecord == null) {
            return acquisitionRecordingUnconfirmed(
                acquireResult = acquireResult,
                recordResult = acquisitionExecuted.recordResult,
            )
        }
        operationRecords += acquisitionRecord

        if (acquireResult is QueueAcquireResult.NoEntries) {
            return CircuitBreakerQueueProcessingResult.NoWork(acquisitionRecord)
        }

        val acquired = acquireResult as QueueAcquireResult.Entries
        val entries = acquired.entries
        val acquiredCount = entries.size
        val leaseId = acquired.lease.id

        val violationError = validateAcquisition(
            entries = entries,
            expectedConsumerId = request.acquireRequest.consumerId.value,
        )
        if (violationError != null) {
            return CircuitBreakerQueueProcessingResult.QueueContractViolation(
                error = violationError,
                summary = summary(acquired = acquiredCount),
                acquisitionRecord = acquisitionRecord,
                leaseId = leaseId,
            )
        }

        var executed = 0
        var completed = 0
        var rescheduled = 0
        var deferred = 0
        var failed = 0
        var cancelled = 0
        var earliestRescheduledAt: DataLoomInstant? = null
        var earliestDeferredAt: DataLoomInstant? = null

        for (entry in entries) {
            val outcome = executionHandler.execute(entry)
            executed++

            val transition = executeTransition(
                entry = entry,
                leaseId = leaseId,
                outcome = outcome,
            )
            val transitionPreExecution = transition.execution.toPreExecutionDecision()
            if (transitionPreExecution != null) {
                return CircuitBreakerQueueProcessingResult.PreExecutionStopped(
                    operation = transition.operation,
                    stage = transition.stage,
                    decision = transitionPreExecution,
                    summary = summary(
                        acquired = acquiredCount,
                        executed = executed,
                        completed = completed,
                        rescheduled = rescheduled,
                        deferred = deferred,
                        failed = failed,
                        cancelled = cancelled,
                    ),
                    affectedEntryId = entry.id,
                    leaseId = leaseId,
                )
            }

            val transitionExecuted =
                transition.execution as CircuitBreakerExecutionResult.Executed<Unit>
            when (val providerOutcome = transitionExecuted.operationResult) {
                is CircuitProtectedOperationResult.Failure -> {
                    return CircuitBreakerQueueProcessingResult.ProviderFailure(
                        operation = transition.operation,
                        stage = transition.stage,
                        error = providerOutcome.error,
                        disposition = QueueCircuitProviderFailureDisposition.CIRCUIT_FAILURE,
                        recordResult = transitionExecuted.recordResult,
                        summary = summary(
                            acquired = acquiredCount,
                            executed = executed,
                            completed = completed,
                            rescheduled = rescheduled,
                            deferred = deferred,
                            failed = failed,
                            cancelled = cancelled,
                        ),
                        affectedEntryId = entry.id,
                        leaseId = leaseId,
                    )
                }
                is CircuitProtectedOperationResult.NonCircuitFailure -> {
                    return CircuitBreakerQueueProcessingResult.ProviderFailure(
                        operation = transition.operation,
                        stage = transition.stage,
                        error = providerOutcome.error,
                        disposition = QueueCircuitProviderFailureDisposition.NON_CIRCUIT_FAILURE,
                        recordResult = transitionExecuted.recordResult,
                        summary = summary(
                            acquired = acquiredCount,
                            executed = executed,
                            completed = completed,
                            rescheduled = rescheduled,
                            deferred = deferred,
                            failed = failed,
                            cancelled = cancelled,
                        ),
                        affectedEntryId = entry.id,
                        leaseId = leaseId,
                    )
                }
                is CircuitProtectedOperationResult.Success -> {
                    when (outcome) {
                        is QueueEntryExecutionOutcome.Completed -> completed++
                        is QueueEntryExecutionOutcome.Reschedule -> {
                            rescheduled++
                            earliestRescheduledAt = earlierOf(
                                earliestRescheduledAt,
                                outcome.availableAt,
                            )
                        }
                        is QueueEntryExecutionOutcome.Deferred -> {
                            deferred++
                            earliestDeferredAt = earlierOf(
                                earliestDeferredAt,
                                outcome.availableAt,
                            )
                        }
                        is QueueEntryExecutionOutcome.Failed -> failed++
                        is QueueEntryExecutionOutcome.Cancelled -> cancelled++
                    }

                    val record = acceptedRecord(
                        operation = transition.operation,
                        affectedEntryId = entry.id,
                        recordResult = transitionExecuted.recordResult,
                    )
                    if (record == null) {
                        return CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed(
                            operation = transition.operation,
                            stage = transition.stage,
                            recordResult = transitionExecuted.recordResult,
                            summary = summary(
                                acquired = acquiredCount,
                                executed = executed,
                                completed = completed,
                                rescheduled = rescheduled,
                                deferred = deferred,
                                failed = failed,
                                cancelled = cancelled,
                            ),
                            affectedEntryIds = listOf(entry.id),
                            leaseId = leaseId,
                        )
                    }
                    operationRecords += record
                }
            }
        }

        return CircuitBreakerQueueProcessingResult.Processed(
            summary = summary(
                acquired = acquiredCount,
                executed = executed,
                completed = completed,
                rescheduled = rescheduled,
                deferred = deferred,
                failed = failed,
                cancelled = cancelled,
            ),
            acquisitionLimitReached = acquiredCount >= request.acquireRequest.maxEntries,
            earliestRescheduledAt = earliestRescheduledAt,
            earliestDeferredAt = earliestDeferredAt,
            operationRecords = operationRecords.toList(),
        )
    }

    private suspend fun executeTransition(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
    ): TransitionExecution = when (outcome) {
        is QueueEntryExecutionOutcome.Completed -> TransitionExecution(
            operation = QueueCircuitOperation.COMPLETE,
            stage = QueueProcessingFailureStage.COMPLETION_TRANSITION,
            execution = queueOperationAdapter.complete(
                scope = scopes.completion,
                request = QueueCompletionRequest(
                    entryId = entry.id,
                    leaseId = leaseId,
                    completedAt = outcome.completedAt,
                ),
            ),
        )
        is QueueEntryExecutionOutcome.Reschedule -> TransitionExecution(
            operation = QueueCircuitOperation.RESCHEDULE,
            stage = QueueProcessingFailureStage.RESCHEDULE_TRANSITION,
            execution = queueOperationAdapter.reschedule(
                scope = scopes.reschedule,
                request = QueueRescheduleRequest(
                    entryId = entry.id,
                    leaseId = leaseId,
                    retryAttempt = outcome.retryAttempt,
                    availableAt = outcome.availableAt,
                    error = outcome.error,
                    retryBudgetState = outcome.retryBudgetState,
                ),
            ),
        )
        is QueueEntryExecutionOutcome.Deferred -> TransitionExecution(
            operation = QueueCircuitOperation.DEFER,
            stage = QueueProcessingFailureStage.DEFERRAL_TRANSITION,
            execution = queueOperationAdapter.defer(
                scope = scopes.deferral,
                request = QueueDeferralRequest(
                    entryId = entry.id,
                    leaseId = leaseId,
                    availableAt = outcome.availableAt,
                    reason = outcome.reason,
                ),
            ),
        )
        is QueueEntryExecutionOutcome.Failed -> TransitionExecution(
            operation = QueueCircuitOperation.FAIL,
            stage = QueueProcessingFailureStage.FAILURE_TRANSITION,
            execution = queueOperationAdapter.fail(
                scope = scopes.failure,
                request = QueueFailureRequest(
                    entryId = entry.id,
                    leaseId = leaseId,
                    error = outcome.error,
                    disposition = outcome.disposition,
                ),
            ),
        )
        is QueueEntryExecutionOutcome.Cancelled -> TransitionExecution(
            operation = QueueCircuitOperation.CANCEL,
            stage = QueueProcessingFailureStage.CANCELLATION_TRANSITION,
            execution = queueOperationAdapter.cancel(
                scope = scopes.cancellation,
                request = QueueCancellationRequest(
                    entryId = entry.id,
                    context = outcome.context,
                ),
            ),
        )
    }

    private fun acquisitionRecordingUnconfirmed(
        acquireResult: QueueAcquireResult,
        recordResult: CircuitBreakerRecordResult,
    ): CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed = when (acquireResult) {
        QueueAcquireResult.NoEntries -> {
            CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed(
                operation = QueueCircuitOperation.ACQUIRE,
                stage = QueueProcessingFailureStage.ACQUISITION,
                recordResult = recordResult,
                summary = zeroSummary(),
            )
        }
        is QueueAcquireResult.Entries -> {
            CircuitBreakerQueueProcessingResult.CircuitRecordingUnconfirmed(
                operation = QueueCircuitOperation.ACQUIRE,
                stage = QueueProcessingFailureStage.ACQUISITION,
                recordResult = recordResult,
                summary = summary(acquired = acquireResult.entries.size),
                affectedEntryIds = acquireResult.entries.map { it.id },
                leaseId = acquireResult.lease.id,
            )
        }
    }

    private fun <T> CircuitBreakerExecutionResult<T>.toPreExecutionDecision():
        QueueCircuitPreExecutionDecision? = when (this) {
        is CircuitBreakerExecutionResult.Executed -> null
        is CircuitBreakerExecutionResult.Rejected -> QueueCircuitPreExecutionDecision.Rejected(
            reason = reason,
            retryAt = retryAt,
        )
        is CircuitBreakerExecutionResult.PermissionPersistenceFailure -> {
            QueueCircuitPreExecutionDecision.PermissionPersistenceFailure(error)
        }
        CircuitBreakerExecutionResult.PermissionContentionLimitReached -> {
            QueueCircuitPreExecutionDecision.PermissionContentionLimitReached
        }
    }

    private fun acceptedRecord(
        operation: QueueCircuitOperation,
        affectedEntryId: QueueEntryId?,
        recordResult: CircuitBreakerRecordResult,
    ): QueueCircuitOperationRecord? = if (
        recordResult is CircuitBreakerRecordResult.Recorded ||
        recordResult is CircuitBreakerRecordResult.Ignored
    ) {
        QueueCircuitOperationRecord(
            operation = operation,
            affectedEntryId = affectedEntryId,
            recordResult = recordResult,
        )
    } else {
        null
    }

    private fun validateScope(
        scope: io.dataloom.api.circuit.CircuitBreakerScope,
        operation: QueueCircuitOperation,
    ) {
        require(
            scope.providerId == null ||
                scope.providerId == queueOperationAdapter.descriptor.id,
        ) {
            "Queue processing circuit scope provider must match the queue provider."
        }
        require(scope.operation == null || scope.operation == operation.retryOperation) {
            "Queue processing circuit scope operation must match ${operation.retryOperation.value}."
        }
    }

    private fun validateAcquisition(
        entries: List<QueueEntry>,
        expectedConsumerId: String,
    ): DataLoomError? {
        val seenIds = mutableSetOf<String>()
        for ((index, entry) in entries.withIndex()) {
            val entryIdValue = entry.id.value
            if (!seenIds.add(entryIdValue)) {
                return ContractViolationError(
                    "Duplicate QueueEntryId \"$entryIdValue\" found at index $index in " +
                        "acquisition result. Each entry must have a unique identifier.",
                )
            }
            val entryConsumerId = entry.lease?.consumerId?.value
            if (entryConsumerId != null && entryConsumerId != expectedConsumerId) {
                return ContractViolationError(
                    "QueueEntry at index $index has consumerId \"$entryConsumerId\" that does " +
                        "not match the expected consumerId \"$expectedConsumerId\". All acquired " +
                        "entries must belong to the requesting consumer.",
                )
            }
        }
        return null
    }

    private fun earlierOf(
        current: DataLoomInstant?,
        candidate: DataLoomInstant,
    ): DataLoomInstant = if (
        current == null || candidate.epochMilliseconds < current.epochMilliseconds
    ) {
        candidate
    } else {
        current
    }

    private fun zeroSummary(): QueueProcessingSummary = summary()

    private fun summary(
        acquired: Int = 0,
        executed: Int = 0,
        completed: Int = 0,
        rescheduled: Int = 0,
        deferred: Int = 0,
        failed: Int = 0,
        cancelled: Int = 0,
    ): QueueProcessingSummary = QueueProcessingSummary(
        acquired = acquired,
        executed = executed,
        completed = completed,
        rescheduled = rescheduled,
        deferred = deferred,
        failed = failed,
        cancelled = cancelled,
    )

    private data class TransitionExecution(
        val operation: QueueCircuitOperation,
        val stage: QueueProcessingFailureStage,
        val execution: CircuitBreakerExecutionResult<Unit>,
    )

    private data class ContractViolationError(
        override val message: String,
        override val code: ErrorCode = ErrorCode("DL-Q-CONTRACT-VIOLATION"),
        override val category: ErrorCategory = ErrorCategory.QUEUE,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val cause: Throwable? = null,
    ) : DataLoomError {
        override fun toString(): String = safeDiagnosticString()
    }
}
