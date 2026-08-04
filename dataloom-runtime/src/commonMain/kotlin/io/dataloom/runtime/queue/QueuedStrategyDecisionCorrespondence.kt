package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.error.ErrorCategory
import io.dataloom.api.error.ErrorCode
import io.dataloom.api.error.ErrorSeverity
import io.dataloom.api.error.Recoverability
import io.dataloom.api.queue.QueueEntry
import io.dataloom.api.strategy.PersistedStrategyDecision

/**
 * Fail-closed correspondence check between durable queue state and resolved work.
 *
 * The application-owned resolver must return the exact strategy decision stored
 * on the acquired queue entry. A changed, dropped, or invented decision stops
 * before timeout enforcement, clock access, coordinator execution, provider
 * resolution, retry evaluation, or any queue transition.
 */
internal object QueuedStrategyDecisionCorrespondence {

    fun validate(
        entry: QueueEntry,
        work: QueuedSynchronizationWork,
    ): DataLoomError? = validate(
        durableDecision = entry.strategyDecision,
        resolvedDecision = work.strategyDecision,
    )

    internal fun validate(
        durableDecision: PersistedStrategyDecision?,
        resolvedDecision: PersistedStrategyDecision?,
    ): DataLoomError? = if (durableDecision == resolvedDecision) {
        null
    } else {
        QueuedStrategyDecisionMismatchError()
    }

    /** Canonical, redacted resolver-contract failure. */
    private data class QueuedStrategyDecisionMismatchError(
        override val code: ErrorCode = ErrorCode("DL-Q-STRATEGY-DECISION-MISMATCH"),
        override val category: ErrorCategory = ErrorCategory.CONFIGURATION,
        override val severity: ErrorSeverity = ErrorSeverity.ERROR,
        override val recoverability: Recoverability = Recoverability.NON_RECOVERABLE,
        override val message: String =
            "Resolved queued work does not match the durable strategy decision.",
        override val cause: Throwable? = null,
    ) : DataLoomError
}
