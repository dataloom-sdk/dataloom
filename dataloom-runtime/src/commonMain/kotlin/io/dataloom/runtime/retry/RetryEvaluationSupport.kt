package io.dataloom.runtime.retry

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.retry.RetryDecision
import io.dataloom.api.scheduling.SchedulingDelay
import io.dataloom.api.synchronization.SynchronizationResult

/**
 * Package-internal retry evaluation utilities shared by
 * [SynchronizationRetryEvaluator] and [SynchronizationRetryOrchestrator].
 *
 * These functions encapsulate the canonical error-extraction and
 * maximum-delay-selection logic so that both evaluation paths use identical
 * semantics.
 */

/**
 * Extracts the canonical errors from a [SynchronizationResult] eligible for
 * retry evaluation, or returns `null` when the result variant is not
 * evaluable.
 *
 * Returns a non-null, non-empty list for [SynchronizationResult.Failed] and
 * [SynchronizationResult.PartiallySucceeded].
 *
 * Returns `null` for [SynchronizationResult.Succeeded],
 * [SynchronizationResult.Skipped], and [SynchronizationResult.Cancelled].
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
 * Returns the maximum [SchedulingDelay] from any [RetryDecision.Retry]
 * decision in [decisions], or `null` when no decision requests retry.
 *
 * Stop decisions do not contribute a delay value. Decision order does not
 * affect maximum selection.
 */
internal fun selectMaxRetryDelay(decisions: List<RetryDecision>): SchedulingDelay? =
    decisions
        .filterIsInstance<RetryDecision.Retry>()
        .maxOfOrNull { it.delay.milliseconds }
        ?.let { SchedulingDelay(it) }
