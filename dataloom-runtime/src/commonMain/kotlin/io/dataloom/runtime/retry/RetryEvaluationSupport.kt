package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.Recoverability
import io.dataloom.api.model.SynchronizationRequest
import io.dataloom.api.retry.RetryAttempt
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.retry.RetryEvaluationRequest
import io.dataloom.api.retry.RetryOperation
import io.dataloom.api.retry.RetryPolicy
import io.dataloom.api.retry.RetryStopReason
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Package-internal retry evaluation utilities shared by
 * [SynchronizationRetryEvaluator] and [SynchronizationRetryOrchestrator].
 *
 * These functions centralize error extraction, fail-closed protection, bounded
 * hint normalization, policy invocation, hint-minimum enforcement, and
 * maximum-delay selection so every retry path uses identical semantics.
 */

/** Result of centrally protected policy evaluation for one terminal result. */
internal data class EvaluatedRetryDecisions(
    internal val decisions: List<RetryDecision>,
    internal val blockingError: DataLoomError?,
)

/**
 * Extracts canonical errors from a [SynchronizationResult] eligible for retry
 * evaluation, or returns `null` when the result variant is not evaluable.
 */
internal fun extractRetryErrors(result: SynchronizationResult): List<DataLoomError>? =
    when (result) {
        is SynchronizationResult.Failed -> listOf(result.error)
        is SynchronizationResult.PartiallySucceeded -> result.errors
        is SynchronizationResult.Succeeded,
        is SynchronizationResult.Skipped,
        is SynchronizationResult.Cancelled,
        -> null
    }

/**
 * Evaluates [retryPolicy] only when the complete failure set is safe for policy
 * evaluation.
 *
 * When [hintEvaluator] is configured, each opt-in error hint is clamped before
 * it is placed in [RetryEvaluationRequest]. The policy may stop or request a
 * longer delay. A retry decision is then adjusted centrally so it cannot be
 * shorter than the bounded hint.
 *
 * A protected error blocks the whole batch before policy or hint evaluation.
 */
internal fun evaluateRetryDecisions(
    retryPolicy: RetryPolicy,
    synchronizationRequest: SynchronizationRequest,
    retryOperation: RetryOperation,
    retryAttempt: RetryAttempt,
    errors: List<DataLoomError>,
    hintEvaluator: RetryHintEvaluator? = null,
): EvaluatedRetryDecisions {
    val firstBlockingError = errors.firstOrNull { error ->
        protectedRetryStopReason(error) != null
    }

    if (firstBlockingError != null) {
        return EvaluatedRetryDecisions(
            decisions = errors.map { error ->
                RetryDecision.Stop(
                    reason = protectedRetryStopReason(error) ?: RetryStopReason.POLICY_REJECTED,
                )
            },
            blockingError = firstBlockingError,
        )
    }

    return EvaluatedRetryDecisions(
        decisions = errors.map { error ->
            val boundedHint = hintEvaluator?.boundedHint(error)
            val decision = retryPolicy.evaluate(
                RetryEvaluationRequest(
                    synchronizationRequest = synchronizationRequest,
                    operation = retryOperation,
                    error = error,
                    attempt = retryAttempt,
                    previousDelay = null,
                    provider = null,
                    retryDelayHint = boundedHint,
                ),
            )
            hintEvaluator?.apply(
                decision = decision,
                boundedHint = boundedHint,
            ) ?: decision
        },
        blockingError = null,
    )
}

/**
 * Returns the mandatory stop reason for an error protected from automatic retry,
 * or `null` when a configured policy may evaluate the error.
 */
internal fun protectedRetryStopReason(error: DataLoomError): RetryStopReason? = when {
    error.recoverability == Recoverability.NON_RECOVERABLE -> RetryStopReason.NON_RECOVERABLE
    error.recoverability == Recoverability.UNKNOWN -> RetryStopReason.POLICY_REJECTED
    error.category.isProtectedFromAutomaticRetry() -> RetryStopReason.POLICY_REJECTED
    else -> null
}

private fun ErrorCategory.isProtectedFromAutomaticRetry(): Boolean = when (this) {
    ErrorCategory.AUTHENTICATION,
    ErrorCategory.AUTHORIZATION,
    ErrorCategory.SERIALIZATION,
    ErrorCategory.VALIDATION,
    ErrorCategory.CONFIGURATION,
    ErrorCategory.POLICY,
    ErrorCategory.CONFLICT,
    ErrorCategory.SECURITY,
    -> true

    ErrorCategory.NETWORK,
    ErrorCategory.STORAGE,
    ErrorCategory.QUEUE,
    ErrorCategory.SCHEDULER,
    ErrorCategory.STATE,
    ErrorCategory.PROVIDER,
    ErrorCategory.PLUGIN,
    ErrorCategory.INTERNAL,
    -> false
}

/**
 * Returns the maximum [SchedulingDelay] from any [RetryDecision.Retry] decision,
 * or `null` when no decision requests retry.
 */
internal fun selectMaxRetryDelay(decisions: List<RetryDecision>): SchedulingDelay? =
    decisions
        .filterIsInstance<RetryDecision.Retry>()
        .maxOfOrNull { it.delay.milliseconds }
        ?.let { SchedulingDelay(it) }
