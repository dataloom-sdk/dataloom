package io.dataloom.runtime.queue

import io.dataloom.api.error.DataLoomError
import io.dataloom.api.identifier.QueueEntryId
import io.dataloom.api.identifier.QueueLeaseId

/**
 * Sealed result returned by [DurableQueueExecutionProcessor.process].
 *
 * Each variant represents a distinct terminal outcome of one processing cycle.
 *
 * ## Variants
 *
 * - [NoWork] — the provider returned no eligible entries. No handler was
 *   invoked.
 * - [Processed] — the processor acquired entries and ran the execution cycle
 *   to completion without a provider failure. The [Processed.summary] reflects
 *   the full truthful counts.
 * - [QueueProviderFailure] — a [io.dataloom.api.provider.QueueProvider]
 *   operation failed. Execution stopped at that point. The [QueueProviderFailure.summary]
 *   reflects truthful partial counts up to the point of failure.
 * - [QueueContractViolation] — the provider returned a structurally invalid
 *   acquisition result (for example: duplicate entry identifiers, or
 *   consumer-identity mismatch). No handler was invoked.
 *
 * ## CancellationException
 *
 * A thrown [kotlin.coroutines.cancellation.CancellationException] from
 * acquisition, any handler invocation, or any provider transition propagates
 * normally and is never converted into a result variant.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public sealed interface QueueProcessingResult {

    /**
     * The provider returned no eligible entries during acquisition.
     *
     * No handler was invoked and no queue transitions were performed.
     */
    public data object NoWork : QueueProcessingResult

    /**
     * The processor acquired entries and ran the execution cycle to
     * completion without a provider failure.
     *
     * [summary] reflects the full truthful counts of the completed cycle.
     *
     * @param summary immutable counters describing the completed cycle.
     */
    public data class Processed(
        /** Immutable counters describing the completed processing cycle. */
        public val summary: QueueProcessingSummary,
    ) : QueueProcessingResult

    /**
     * A [io.dataloom.api.provider.QueueProvider] operation failed during the
     * processing cycle.
     *
     * Execution stopped at the point of failure. The [summary] reflects
     * truthful partial counts of work completed before the failure.
     *
     * ## Stale-lease failure
     *
     * A stale-lease rejection is reported here at the appropriate transition
     * stage. The processor does not retry stale-lease failures.
     *
     * @param error the exact [DataLoomError] from the failed provider
     *   operation.
     * @param stage the processing stage at which the provider failed.
     * @param summary immutable counters describing truthful partial progress
     *   up to the point of failure.
     * @param affectedEntryId optional identifier of the entry for which the
     *   transition failed. Present for transition-stage failures; absent for
     *   acquisition-stage failures.
     * @param leaseId optional identifier of the lease held at the time of
     *   failure. Present for transition-stage failures; absent for
     *   acquisition-stage failures.
     */
    public data class QueueProviderFailure(
        /** The exact [DataLoomError] from the failed provider operation. */
        public val error: DataLoomError,

        /** The processing stage at which the provider failed. */
        public val stage: QueueProcessingFailureStage,

        /**
         * Immutable counters describing truthful partial progress up to the
         * point of failure.
         */
        public val summary: QueueProcessingSummary,

        /**
         * Optional identifier of the entry for which the transition failed.
         *
         * Present for transition-stage failures; absent for
         * acquisition-stage failures.
         */
        public val affectedEntryId: QueueEntryId? = null,

        /**
         * Optional identifier of the lease held at the time of failure.
         *
         * Present for transition-stage failures; absent for
         * acquisition-stage failures.
         */
        public val leaseId: QueueLeaseId? = null,
    ) : QueueProcessingResult

    /**
     * The provider returned a structurally invalid acquisition result that
     * violates the acquisition contract.
     *
     * Structural violations include:
     * - Duplicate [io.dataloom.api.queue.QueueEntryId] values within the same
     *   acquisition batch.
     * - An entry whose [io.dataloom.api.queue.QueueEntry.lease] carries a
     *   [io.dataloom.api.queue.QueueLease.consumerId] that does not match the
     *   [io.dataloom.api.queue.QueueAcquireRequest.consumerId].
     *
     * No handler is invoked and no queue transitions are performed.
     *
     * [summary] reflects only the [QueueProcessingSummary.acquired] count;
     * all other counters are zero.
     *
     * @param error a canonical [DataLoomError] describing the contract
     *   violation.
     * @param summary immutable counters with only [QueueProcessingSummary.acquired]
     *   set; all other counters are zero.
     */
    public data class QueueContractViolation(
        /**
         * Canonical [DataLoomError] describing the contract violation.
         */
        public val error: DataLoomError,

        /**
         * Immutable counters with only [QueueProcessingSummary.acquired] set;
         * all other counters are zero.
         */
        public val summary: QueueProcessingSummary,
    ) : QueueProcessingResult
}
