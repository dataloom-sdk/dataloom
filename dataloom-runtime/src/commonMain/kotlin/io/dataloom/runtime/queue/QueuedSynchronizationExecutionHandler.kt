package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.queue.QueueFailureDisposition
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.synchronization.SynchronizationResult
import io.dataloom.api.time.DataLoomClock
import io.dataloom.api.time.DataLoomInstant
import io.dataloom.runtime.connectivity.SynchronizationConnectivityConfiguration
import io.dataloom.runtime.execution.SynchronizationExecutionCoordinator
import io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason
import io.dataloom.runtime.execution.SynchronizationExecutionResult
import io.dataloom.runtime.retry.SynchronizationRetryEvaluation
import io.dataloom.runtime.retry.SynchronizationRetryEvaluator

/**
 * [QueueEntryExecutionHandler] implementation that orchestrates queued
 * synchronization execution and retry integration.
 *
 * ## Flow
 *
 * [execute] follows this strict, deterministic sequence per entry:
 *
 * 1. Invoke [workResolver] with the exact acquired [QueueEntry].
 * 2. If [QueuedSynchronizationWorkResolution.Rejected], return
 *    [QueueEntryExecutionOutcome.Failed] with the canonical error and
 *    [QueueFailureDisposition.FAILED].
 * 3. Invoke [executionCoordinator] with the exact [QueuedSynchronizationWork]
 *    request and bindings.
 * 4. If [SynchronizationExecutionResult.Rejected], map the structural
 *    rejection to [QueueEntryExecutionOutcome.Failed] with
 *    [QueueFailureDisposition.FAILED].
 * 5. If [SynchronizationExecutionResult.Executed]:
 *    - [SynchronizationResult.Succeeded] or [SynchronizationResult.Skipped]
 *      → [QueueEntryExecutionOutcome.Completed] with `result.completedAt`.
 *    - [SynchronizationResult.Cancelled] → [QueueEntryExecutionOutcome.Cancelled]
 *      with the request execution context.
 *    - [SynchronizationResult.Failed] or [SynchronizationResult.PartiallySucceeded]
 *      → invoke [retryEvaluator].
 * 6. Retry evaluation:
 *    - [SynchronizationRetryEvaluation.ShouldRetry] →
 *      [QueueEntryExecutionOutcome.Reschedule] with the exact retry attempt,
 *      the computed availability instant, and the primary canonical error.
 *    - [SynchronizationRetryEvaluation.StopRetry] →
 *      [QueueEntryExecutionOutcome.Failed] with the primary canonical error and
 *      [QueueFailureDisposition.FAILED].
 *    - [SynchronizationRetryEvaluation.NotRequired] → unreachable for
 *      Failed/PartiallySucceeded; treated defensively as
 *      [QueueEntryExecutionOutcome.Completed].
 *
 * ## RetryAttempt computation
 *
 * The retry attempt number supplied to [retryEvaluator] is
 * `(entry.retryAttempt?.number ?: 0) + 1`. This represents the attempt count
 * for the next retry cycle. The same value is stored on the rescheduled entry
 * via [QueueEntryExecutionOutcome.Reschedule.retryAttempt]. The evaluator
 * passes this value unchanged to [io.dataloom.api.retry.RetryPolicy.evaluate].
 *
 * ## Coordinator invocation
 *
 * [executionCoordinator] is invoked exactly once per entry. The handler does
 * not retry the coordinator call.
 *
 * ## Cancellation
 *
 * [kotlinx.coroutines.CancellationException] from [executionCoordinator] or
 * [retryEvaluator] propagates normally. It is never converted into a
 * [QueueEntryExecutionOutcome].
 *
 * ## Exception boundary
 *
 * Unexpected exceptions from [workResolver], [executionCoordinator], or
 * [retryEvaluator] propagate to the caller without modification. This handler
 * does not swallow arbitrary programming errors.
 *
 * ## Clock access
 *
 * When [connectivityConfiguration] is provided with a non-NONE requirement,
 * [clock] is read exactly once when a connectivity rejection is mapped to
 * [QueueEntryExecutionOutcome.Reschedule]. The completion instant for
 * [QueueEntryExecutionOutcome.Completed] is taken from
 * [io.dataloom.api.synchronization.SynchronizationResult.completedAt], which
 * was recorded by the synchronization pipeline at the point of completion.
 * Clock access for retry availability timestamps is performed solely by the
 * injected [retryEvaluator].
 *
 * ## Boundaries
 *
 * This handler must not:
 * - Access [io.dataloom.api.queue.QueueProvider] directly.
 * - Invoke [io.dataloom.api.scheduling.SchedulerProvider].
 * - Own a [kotlinx.coroutines.CoroutineScope] or select a dispatcher.
 * - Use global state, reflection, ServiceLoader, or a DI framework.
 * - Expose credentials, tokens, encryption keys, personal data, or full
 *   payloads through any outcome field.
 * - Use Android or JVM-only APIs.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library, DataLoom API, and DataLoom runtime types only.
 * Safe for use in Kotlin Multiplatform common code.
 *
 * @param workResolver the application-owned resolver that maps a [QueueEntry]
 *   to a [QueuedSynchronizationWork]. Required.
 * @param executionCoordinator the coordinator that executes synchronization
 *   pipelines. Required. Invoked exactly once per entry.
 * @param retryEvaluator the evaluator that assesses retry policy for
 *   terminal failure results. Required. Contains its own [io.dataloom.api.time.DataLoomClock]
 *   for computing retry availability timestamps.
 * @param retryOperation the logical operation identifier passed to the retry
 *   evaluator and therefore to [io.dataloom.api.retry.RetryPolicy.evaluate].
 *   Required.
 * @param connectivityConfiguration the optional
 *   [SynchronizationConnectivityConfiguration] used to determine the offline
 *   reschedule delay when a connectivity rejection is received. When `null`,
 *   connectivity rejections are mapped to [QueueEntryExecutionOutcome.Failed].
 *   Defaults to `null` for backward compatibility.
 * @param clock the optional [DataLoomClock] used to read the current instant
 *   when computing the offline-deferral availability timestamp. Required when
 *   [connectivityConfiguration] is non-null and its requirement may not be
 *   satisfied. Defaults to `null` for backward compatibility.
 */
internal class QueuedSynchronizationExecutionHandler(
    private val workResolver: QueuedSynchronizationWorkResolver,
    private val executionCoordinator: SynchronizationExecutionCoordinator,
    private val retryEvaluator: SynchronizationRetryEvaluator,
    private val retryOperation: RetryOperation,
    private val connectivityConfiguration: SynchronizationConnectivityConfiguration? = null,
    private val clock: DataLoomClock? = null,
) : QueueEntryExecutionHandler {

    /**
     * Executes the supplied [entry] by resolving work, invoking the
     * synchronization coordinator, and mapping the result to a
     * [QueueEntryExecutionOutcome].
     *
     * Follows the deterministic execution flow documented on
     * [QueuedSynchronizationExecutionHandler].
     *
     * [kotlinx.coroutines.CancellationException] propagates normally and is
     * never converted to a [QueueEntryExecutionOutcome]. Unexpected exceptions
     * propagate unchanged.
     *
     * @param entry the exact acquired [QueueEntry] to execute.
     * @return a [QueueEntryExecutionOutcome] describing the desired queue
     *   transition.
     */
    override suspend fun execute(entry: QueueEntry): QueueEntryExecutionOutcome {

        // Step 1–2: Resolve work; reject structurally if resolution fails.
        val resolution = workResolver.resolve(entry)
        if (resolution is QueuedSynchronizationWorkResolution.Rejected) {
            return QueueEntryExecutionOutcome.Failed(
                error = resolution.error,
                disposition = QueueFailureDisposition.FAILED,
            )
        }
        val work = (resolution as QueuedSynchronizationWorkResolution.Resolved).work

        // Step 3–4: Execute synchronization; map coordinator rejections.
        val executionResult = executionCoordinator.execute(work.request, work.bindings)
        if (executionResult is SynchronizationExecutionResult.Rejected) {
            return mapCoordinatorRejection(executionResult, entry)
        }

        // Step 5: Map the pipeline result to a queue outcome.
        val syncResult = (executionResult as SynchronizationExecutionResult.Executed).result
        return mapSynchronizationResult(syncResult, entry)
    }

    /**
     * Maps a [SynchronizationExecutionResult.Rejected] to a
     * [QueueEntryExecutionOutcome].
     *
     * - [SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET]:
     *   when [connectivityConfiguration] and [clock] are both available,
     *   returns [QueueEntryExecutionOutcome.Reschedule] using the offline
     *   reschedule delay. Otherwise returns [QueueEntryExecutionOutcome.Failed].
     * - [SynchronizationExecutionRejectionReason.CONNECTIVITY_PROVIDER_NOT_CONFIGURED]:
     *   returns [QueueEntryExecutionOutcome.Failed] with a structural
     *   configuration error.
     * - [SynchronizationExecutionRejectionReason.CONNECTIVITY_CHECK_FAILED]:
     *   returns [QueueEntryExecutionOutcome.Failed] preserving the exact
     *   canonical [DataLoomError] from [SynchronizationExecutionResult.Rejected.connectivityCheckError].
     * - All other reasons: returns [QueueEntryExecutionOutcome.Failed] with a
     *   structural rejection error.
     */
    private fun mapCoordinatorRejection(
        rejected: SynchronizationExecutionResult.Rejected,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome = when (rejected.reason) {

        SynchronizationExecutionRejectionReason.CONNECTIVITY_REQUIREMENT_NOT_MET -> {
            val config = connectivityConfiguration
            val offlineClock = clock
            if (config != null && offlineClock != null) {
                offlineDeferralReschedule(config, offlineClock, entry)
            } else {
                QueueEntryExecutionOutcome.Failed(
                    error = structuralRejectionError(rejected),
                    disposition = QueueFailureDisposition.FAILED,
                )
            }
        }

        SynchronizationExecutionRejectionReason.CONNECTIVITY_PROVIDER_NOT_CONFIGURED ->
            QueueEntryExecutionOutcome.Failed(
                error = connectivityProviderMissingError(),
                disposition = QueueFailureDisposition.FAILED,
            )

        SynchronizationExecutionRejectionReason.CONNECTIVITY_CHECK_FAILED ->
            QueueEntryExecutionOutcome.Failed(
                error = rejected.connectivityCheckError ?: structuralRejectionError(rejected),
                disposition = QueueFailureDisposition.FAILED,
            )

        else ->
            QueueEntryExecutionOutcome.Failed(
                error = structuralRejectionError(rejected),
                disposition = QueueFailureDisposition.FAILED,
            )
    }

    /**
     * Computes an offline deferral [QueueEntryExecutionOutcome.Reschedule].
     *
     * Reads [clock] exactly once. Calculates next availability as
     * `clock.now().epochMilliseconds + config.offlineRescheduleDelay.milliseconds`
     * using overflow-safe arithmetic. The retry attempt is taken from the
     * entry without increment.
     *
     * [io.dataloom.api.retry.RetryPolicy] is not invoked.
     * [io.dataloom.api.scheduling.SchedulerProvider] is not invoked.
     * [io.dataloom.api.queue.QueueProvider] is not invoked.
     */
    private fun offlineDeferralReschedule(
        config: SynchronizationConnectivityConfiguration,
        offlineClock: DataLoomClock,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome.Reschedule {
        val nowMillis = offlineClock.now().epochMilliseconds
        val delayMillis = config.offlineRescheduleDelay.milliseconds
        val availableAtMillis = addMillisOverflowSafe(nowMillis, delayMillis)
        val deferralAttempt = entry.retryAttempt ?: RetryAttempt(1)
        return QueueEntryExecutionOutcome.Reschedule(
            retryAttempt = deferralAttempt,
            availableAt = DataLoomInstant(epochMilliseconds = availableAtMillis),
            error = offlineDeferralError(),
        )
    }

    /**
     * Adds [delayMillis] to [epochMillis] with overflow-safe semantics.
     *
     * Returns [Long.MAX_VALUE] when the sum overflows.
     */
    private fun addMillisOverflowSafe(epochMillis: Long, delayMillis: Long): Long {
        val sum = epochMillis + delayMillis
        return if (sum < 0L) Long.MAX_VALUE else sum
    }

    /**
     * Maps a [SynchronizationResult] to a [QueueEntryExecutionOutcome].
     *
     * - [SynchronizationResult.Succeeded] and [SynchronizationResult.Skipped]
     *   → [QueueEntryExecutionOutcome.Completed] with `result.completedAt`.
     * - [SynchronizationResult.Cancelled] → [QueueEntryExecutionOutcome.Cancelled]
     *   with the request execution context.
     * - [SynchronizationResult.Failed] and [SynchronizationResult.PartiallySucceeded]
     *   → delegated to [evaluateRetry].
     */
    private fun mapSynchronizationResult(
        result: SynchronizationResult,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome =
        when (result) {
            is SynchronizationResult.Succeeded,
            is SynchronizationResult.Skipped,
            -> QueueEntryExecutionOutcome.Completed(completedAt = result.completedAt)

            is SynchronizationResult.Cancelled ->
                QueueEntryExecutionOutcome.Cancelled(context = result.request.context)

            is SynchronizationResult.Failed,
            is SynchronizationResult.PartiallySucceeded,
            -> evaluateRetry(result, entry)
        }

    /**
     * Evaluates retry policy for a failed synchronization result and returns
     * the corresponding [QueueEntryExecutionOutcome].
     *
     * The retry attempt number passed to the evaluator is
     * `(entry.retryAttempt?.number ?: 0) + 1`, representing the attempt count
     * for the next retry cycle.
     *
     * - [SynchronizationRetryEvaluation.ShouldRetry] →
     *   [QueueEntryExecutionOutcome.Reschedule].
     * - [SynchronizationRetryEvaluation.StopRetry] →
     *   [QueueEntryExecutionOutcome.Failed].
     * - [SynchronizationRetryEvaluation.NotRequired] → defensive
     *   [QueueEntryExecutionOutcome.Completed] with `result.completedAt`
     *   (should not occur for Failed or PartiallySucceeded results).
     */
    private fun evaluateRetry(
        result: SynchronizationResult,
        entry: QueueEntry,
    ): QueueEntryExecutionOutcome {
        // Compute the next attempt number for policy evaluation and reschedule.
        val nextAttemptNumber = (entry.retryAttempt?.number ?: 0) + 1
        val nextAttempt = RetryAttempt(nextAttemptNumber)

        return when (val evaluation = retryEvaluator.evaluate(result, nextAttempt, retryOperation)) {
            is SynchronizationRetryEvaluation.ShouldRetry ->
                QueueEntryExecutionOutcome.Reschedule(
                    retryAttempt = evaluation.retryAttempt,
                    availableAt = evaluation.availableAt,
                    error = evaluation.error,
                )

            is SynchronizationRetryEvaluation.StopRetry ->
                QueueEntryExecutionOutcome.Failed(
                    error = evaluation.error,
                    disposition = QueueFailureDisposition.FAILED,
                )

            is SynchronizationRetryEvaluation.NotRequired ->
                // Defensive: NotRequired should not occur for Failed or PartiallySucceeded.
                QueueEntryExecutionOutcome.Completed(completedAt = result.completedAt)
        }
    }

    /**
     * Produces a canonical [DataLoomError] describing a structural rejection
     * from [SynchronizationExecutionResult.Rejected].
     *
     * The error includes only the structural rejection reason and does not
     * expose provider internals, credentials, tokens, encryption keys,
     * personal data, or stack traces.
     */
    private fun structuralRejectionError(
        rejected: SynchronizationExecutionResult.Rejected,
    ): DataLoomError = StructuralRejectionError(
        reason = rejected.reason,
    )

    /**
     * Produces a canonical [DataLoomError] for a missing connectivity provider
     * configuration error.
     *
     * Exposes only a stable error code and structural category. Does not
     * include provider references, credentials, or sensitive state.
     */
    private fun connectivityProviderMissingError(): DataLoomError = ConnectivityProviderMissingError()

    /**
     * Produces a canonical [DataLoomError] for an offline deferral.
     *
     * Used as the error on a [QueueEntryExecutionOutcome.Reschedule] when the
     * connectivity requirement was not met and the entry is deferred.
     * Exposes only a stable error code and safe structural metadata.
     */
    private fun offlineDeferralError(): DataLoomError = OfflineDeferralError()

    /**
     * Minimal canonical [DataLoomError] synthesized from a
     * [io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason].
     *
     * Exposes only structural metadata safe for diagnostic output. Does not
     * include provider references, credentials, tokens, stack traces, or
     * sensitive runtime state.
     */
    private class StructuralRejectionError(
        private val reason: io.dataloom.runtime.execution.SynchronizationExecutionRejectionReason,
    ) : DataLoomError {
        override val code: ErrorCode = ErrorCode("DL-EXEC-REJECTED")
        override val category: ErrorCategory = ErrorCategory.STATE
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
        override val message: String =
            "Synchronization execution rejected before pipeline invocation: $reason"
        override val cause: Throwable? = null
    }

    /**
     * Minimal canonical [DataLoomError] indicating that no connectivity
     * provider is configured for the connectivity requirement.
     *
     * Exposes only a stable error code and structural category. Does not
     * include credentials, payloads, or sensitive state.
     */
    private class ConnectivityProviderMissingError : DataLoomError {
        override val code: ErrorCode = ErrorCode("DL-CONNECTIVITY-PROVIDER-NOT-CONFIGURED")
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION
        override val severity: ErrorSeverity = ErrorSeverity.ERROR
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE
        override val message: String =
            "Synchronization execution rejected: connectivity is required but no " +
                "ConnectivityProvider is configured."
        override val cause: Throwable? = null
    }

    /**
     * Minimal canonical [DataLoomError] for an offline connectivity deferral.
     *
     * Attached to [QueueEntryExecutionOutcome.Reschedule] when the
     * connectivity requirement was not satisfied and the entry is deferred
     * to a future availability time. Exposes only a stable error code and
     * safe structural metadata.
     */
    private class OfflineDeferralError : DataLoomError {
        override val code: ErrorCode = ErrorCode("DL-CONNECTIVITY-REQUIREMENT-NOT-MET")
        override val category: ErrorCategory = ErrorCategory.NETWORK
        override val severity: ErrorSeverity = ErrorSeverity.WARNING
        override val recoverability: Recoverability = Recoverability.RECOVERABLE
        override val message: String =
            "Connectivity requirement not satisfied; synchronization deferred for offline retry."
        override val cause: Throwable? = null
    }
}
