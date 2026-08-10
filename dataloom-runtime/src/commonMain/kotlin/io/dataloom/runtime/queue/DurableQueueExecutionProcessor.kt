package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.error.safeDiagnosticString
import io.dataloom.api.provider.ProviderOperationResult
import io.dataloom.api.queue.QueueProvider
import io.dataloom.api.queue.QueueAcquireResult
import io.dataloom.api.queue.QueueCancellationRequest
import io.dataloom.api.queue.QueueCompletionRequest
import io.dataloom.api.queue.QueueDeferralRequest
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueFailureRequest
import io.dataloom.api.queue.QueueRescheduleRequest
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.time.DataLoomInstant

/**
 * Platform-independent processor that drives one bounded durable queue
 * execution cycle.
 *
 * [DurableQueueExecutionProcessor] sits between [QueueProvider] acquisition
 * and application-level execution. One [process] call:
 *
 * 1. Acquires entries from [queueProvider] using the caller-supplied
 *    [QueueAcquireRequest][io.dataloom.api.queue.QueueAcquireRequest] exactly
 *    once.
 * 2. Returns [QueueProcessingResult.NoWork] when no entries are available.
 * 3. Validates the acquisition result structurally before executing any
 *    handler.
 * 4. Executes each acquired entry through [executionHandler] sequentially in
 *    acquisition-result order.
 * 5. Persists exactly one [QueueProvider] transition per entry based on its
 *    [QueueEntryExecutionOutcome].
 * 6. Returns a structured [QueueProcessingResult] that truthfully reflects
 *    the cycle outcome, including partial progress when an early stop occurs.
 *
 * ## Acquisition
 *
 * Acquisition is performed exactly once per [process] call. No second
 * acquisition occurs regardless of outcome.
 *
 * ## Validation
 *
 * Before any handler is invoked, the acquisition result is validated:
 * - Duplicate [io.dataloom.api.queue.QueueEntryId] values within the batch
 *   cause [QueueProcessingResult.QueueContractViolation].
 * - An entry whose [io.dataloom.api.queue.QueueLease.consumerId] does not
 *   match the request [io.dataloom.api.queue.QueueAcquireRequest.consumerId]
 *   causes [QueueProcessingResult.QueueContractViolation].
 *
 * ## Sequential execution
 *
 * Entries are executed in acquisition-result order. Each entry is presented to
 * [executionHandler] at most once.
 *
 * ## Transition mapping
 *
 * | [QueueEntryExecutionOutcome] | [QueueProvider] call |
 * |---|---|
 * | [QueueEntryExecutionOutcome.Completed] | [QueueProvider.complete] |
 * | [QueueEntryExecutionOutcome.Reschedule] | [QueueProvider.reschedule] |
 * | [QueueEntryExecutionOutcome.Deferred] | [QueueProvider.defer] |
 * | [QueueEntryExecutionOutcome.Failed] | [QueueProvider.fail] |
 * | [QueueEntryExecutionOutcome.Cancelled] | [QueueProvider.cancel] |
 *
 * Every transition uses the exact [io.dataloom.api.queue.QueueLeaseId] and
 * [io.dataloom.api.queue.QueueEntryId] from the acquired entry. No replacement
 * lease is generated.
 *
 * ## Provider failure
 *
 * A [ProviderOperationResult.Failure] from any transition call stops the cycle
 * immediately. Later entries are not executed. The exact [DataLoomError] and
 * the truthful partial [QueueProcessingSummary] are preserved in
 * [QueueProcessingResult.QueueProviderFailure]. Stale-lease failures are
 * surfaced here and are not retried.
 *
 * ## At-least-once execution
 *
 * This processor provides at-least-once execution semantics. A provider
 * transition failure after handler execution may leave an entry in
 * [io.dataloom.api.queue.QueueEntryState.LEASED] state until its lease
 * expires. Lease recovery is performed by
 * [QueueProvider.recoverExpiredLeases].
 *
 * ## Cancellation
 *
 * [kotlin.coroutines.cancellation.CancellationException] from acquisition,
 * any handler invocation, or any provider transition propagates normally and
 * is never converted into a [QueueProcessingResult] variant.
 *
 * An explicit [QueueEntryExecutionOutcome.Cancelled] is a distinct,
 * business-level signal that triggers [QueueProvider.cancel]. It does not
 * propagate as a thrown exception.
 *
 * ## What this processor must not do
 *
 * This processor must not:
 * - Invoke synchronization coordinators or pipelines.
 * - Invoke retry policy or retry orchestrators.
 * - Invoke scheduler or connectivity providers.
 * - Invoke storage, transport, conflict, lifecycle, observer, or event
 *   operations.
 * - Own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher.
 * - Generate identifiers or replacement leases.
 * - Retain completed payload batches after returning.
 * - Log credentials, tokens, keys, personal data, or full payloads.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 *
 * @param queueProvider the [QueueProvider] used for acquisition and
 *   transitions. Required.
 * @param executionHandler the [QueueEntryExecutionHandler] invoked for each
 *   acquired entry. Required.
 */
public class DurableQueueExecutionProcessor(
    private val queueProvider: QueueProvider,
    private val executionHandler: QueueEntryExecutionHandler,
) {

    /**
     * Executes one bounded durable queue processing cycle.
     *
     * Acquisition is performed exactly once. Handler and transition calls are
     * made sequentially per entry.
     *
     * [kotlin.coroutines.cancellation.CancellationException] from acquisition,
     * handler, or any transition propagates normally.
     *
     * @param request the immutable processing request carrying the caller-
     *   supplied [io.dataloom.api.queue.QueueAcquireRequest].
     * @return a [QueueProcessingResult] describing the terminal outcome of
     *   this cycle.
     */
    public suspend fun process(request: QueueProcessingRequest): QueueProcessingResult {
        // Step 1: Acquire exactly once.
        val acquireResult = when (
            val providerResult = queueProvider.acquire(request.acquireRequest)
        ) {
            is ProviderOperationResult.Failure -> return QueueProcessingResult.QueueProviderFailure(
                error = providerResult.error,
                stage = QueueProcessingFailureStage.ACQUISITION,
                summary = zeroSummary(),
            )
            is ProviderOperationResult.Success -> providerResult.value
        }

        // Step 2: Return NoWork when there are no entries.
        if (acquireResult is QueueAcquireResult.NoEntries) {
            return QueueProcessingResult.NoWork
        }

        val acquired = acquireResult as QueueAcquireResult.Entries
        val entries = acquired.entries
        val acquiredCount = entries.size
        val leaseId = acquired.lease.id

        // Step 3: Validate acquisition result before executing any handler.
        val violationError = validateAcquisition(entries, request.acquireRequest.consumerId.value)
        if (violationError != null) {
            return QueueProcessingResult.QueueContractViolation(
                error = violationError,
                summary = QueueProcessingSummary(
                    acquired = acquiredCount,
                    executed = 0,
                    completed = 0,
                    rescheduled = 0,
                    failed = 0,
                    cancelled = 0,
                ),
            )
        }

        // Step 4: Execute entries sequentially and persist one transition each.
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

            val transitionResult = persistTransition(entry, leaseId, outcome)
            when (transitionResult) {
                is TransitionResult.Success -> {
                    when (outcome) {
                        is QueueEntryExecutionOutcome.Completed -> completed++
                        is QueueEntryExecutionOutcome.Reschedule -> {
                            rescheduled++
                            // Track earliest availability only for successfully persisted reschedules.
                            val availableAt = outcome.availableAt
                            val current = earliestRescheduledAt
                            if (current == null || availableAt.epochMilliseconds < current.epochMilliseconds) {
                                earliestRescheduledAt = availableAt
                            }
                        }
                        is QueueEntryExecutionOutcome.Deferred -> {
                            deferred++
                            val availableAt = outcome.availableAt
                            val current = earliestDeferredAt
                            if (current == null ||
                                availableAt.epochMilliseconds < current.epochMilliseconds
                            ) {
                                earliestDeferredAt = availableAt
                            }
                        }
                        is QueueEntryExecutionOutcome.Failed -> failed++
                        is QueueEntryExecutionOutcome.Cancelled -> cancelled++
                    }
                }
                is TransitionResult.Failure -> {
                    return QueueProcessingResult.QueueProviderFailure(
                        error = transitionResult.error,
                        stage = transitionResult.stage,
                        summary = QueueProcessingSummary(
                            acquired = acquiredCount,
                            executed = executed,
                            completed = completed,
                            rescheduled = rescheduled,
                            failed = failed,
                            cancelled = cancelled,
                            deferred = deferred,
                        ),
                        affectedEntryId = entry.id,
                        leaseId = leaseId,
                    )
                }
            }
        }

        return QueueProcessingResult.Processed(
            summary = QueueProcessingSummary(
                acquired = acquiredCount,
                executed = executed,
                completed = completed,
                rescheduled = rescheduled,
                failed = failed,
                cancelled = cancelled,
                deferred = deferred,
            ),
            acquisitionLimitReached = acquiredCount >= request.acquireRequest.maxEntries,
            earliestRescheduledAt = earliestRescheduledAt,
            earliestDeferredAt = earliestDeferredAt,
        )
    }

    /**
     * Validates the structural contract of the acquired entries.
     *
     * Returns a [DataLoomError] when a violation is found, or `null` when the
     * batch is structurally valid.
     */
    private fun validateAcquisition(
        entries: List<QueueEntry>,
        expectedConsumerId: String,
    ): DataLoomError? {
        val seenIds = mutableSetOf<String>()
        for ((index, entry) in entries.withIndex()) {
            val entryIdValue = entry.id.value
            if (!seenIds.add(entryIdValue)) {
                return contractViolationError(
                    "Duplicate QueueEntryId \"$entryIdValue\" found at index $index in " +
                        "acquisition result. Each entry must have a unique identifier.",
                )
            }
            val entryConsumerId = entry.lease?.consumerId?.value
            if (entryConsumerId != null && entryConsumerId != expectedConsumerId) {
                return contractViolationError(
                    "QueueEntry at index $index has consumerId \"$entryConsumerId\" that does " +
                        "not match the expected consumerId \"$expectedConsumerId\". All acquired " +
                        "entries must belong to the requesting consumer.",
                )
            }
        }
        return null
    }

    /**
     * Persists one [QueueProvider] transition for the given [entry] and
     * [outcome].
     *
     * Returns [TransitionResult.Success] when the provider call succeeds or
     * [TransitionResult.Failure] with the exact [DataLoomError] and
     * [QueueProcessingFailureStage] when the provider call fails.
     */
    private suspend fun persistTransition(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
    ): TransitionResult {
        return when (outcome) {
            is QueueEntryExecutionOutcome.Completed -> {
                val result = queueProvider.complete(
                    QueueCompletionRequest(
                        entryId = entry.id,
                        leaseId = leaseId,
                        completedAt = outcome.completedAt,
                    ),
                )
                result.toTransitionResult(QueueProcessingFailureStage.COMPLETION_TRANSITION)
            }
            is QueueEntryExecutionOutcome.Reschedule -> {
                val result = queueProvider.reschedule(
                    QueueRescheduleRequest(
                        entryId = entry.id,
                        leaseId = leaseId,
                        retryAttempt = outcome.retryAttempt,
                        availableAt = outcome.availableAt,
                        error = outcome.error,
                        retryBudgetState = outcome.retryBudgetState,
                    ),
                )
                result.toTransitionResult(QueueProcessingFailureStage.RESCHEDULE_TRANSITION)
            }
            is QueueEntryExecutionOutcome.Deferred -> {
                val result = queueProvider.defer(
                    QueueDeferralRequest(
                        entryId = entry.id,
                        leaseId = leaseId,
                        availableAt = outcome.availableAt,
                        reason = outcome.reason,
                    ),
                )
                result.toTransitionResult(QueueProcessingFailureStage.DEFERRAL_TRANSITION)
            }
            is QueueEntryExecutionOutcome.Failed -> {
                val result = queueProvider.fail(
                    QueueFailureRequest(
                        entryId = entry.id,
                        leaseId = leaseId,
                        error = outcome.error,
                        disposition = outcome.disposition,
                    ),
                )
                result.toTransitionResult(QueueProcessingFailureStage.FAILURE_TRANSITION)
            }
            is QueueEntryExecutionOutcome.Cancelled -> {
                val result = queueProvider.cancel(
                    QueueCancellationRequest(
                        entryId = entry.id,
                        context = outcome.context,
                    ),
                )
                result.toTransitionResult(QueueProcessingFailureStage.CANCELLATION_TRANSITION)
            }
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private sealed interface TransitionResult {
        data object Success : TransitionResult
        data class Failure(
            val error: DataLoomError,
            val stage: QueueProcessingFailureStage,
        ) : TransitionResult
    }

    private fun ProviderOperationResult<Unit>.toTransitionResult(
        stage: QueueProcessingFailureStage,
    ): TransitionResult = when (this) {
        is ProviderOperationResult.Success -> TransitionResult.Success
        is ProviderOperationResult.Failure -> TransitionResult.Failure(
            error = this.error,
            stage = stage,
        )
    }

    private fun zeroSummary(): QueueProcessingSummary = QueueProcessingSummary(
        acquired = 0,
        executed = 0,
        completed = 0,
        rescheduled = 0,
        failed = 0,
        cancelled = 0,
        deferred = 0,
    )

    private fun contractViolationError(message: String): DataLoomError =
        ContractViolationError(message)

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
