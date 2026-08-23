package io.dataloom.runtime.queue

import io.dataloom.api.identifier.QueueLeaseId
import io.dataloom.api.queue.QueueEntry

/**
 * Optional collaborator notified by [DurableQueueExecutionProcessor] immediately
 * after it durably persists one queue-entry transition.
 *
 * ## Why this exists
 *
 * [DurableQueueExecutionProcessor.process] already computes exactly one
 * [QueueEntryExecutionOutcome] per acquired [QueueEntry] and already persists
 * the corresponding [io.dataloom.api.queue.QueueProvider] transition, but
 * before this observer existed nothing outside the processor's own loop ever
 * saw that per-entry pair -- [QueueProcessingResult] only ever returns
 * aggregate counters (see [QueueProcessingResult.Processed.summary]). This
 * observer is the sole per-entry hook. It is purely an optional side channel:
 * it cannot change, delay, or fail the [QueueProcessingResult]
 * [DurableQueueExecutionProcessor.process] returns, and a `null` observer
 * (the default) reproduces byte-for-byte the behavior this codebase had
 * before this type existed.
 *
 * ## Only genuinely persisted transitions are reported
 *
 * [onTransition] is invoked only after the corresponding
 * [io.dataloom.api.queue.QueueProvider] transition call has already returned
 * success -- see [DurableQueueExecutionProcessor]'s own "Transition mapping"
 * table. It is never invoked for an entry whose transition failed (that
 * failure is reported through [QueueProcessingResult.QueueProviderFailure]
 * instead, and the entry's real durable state is unchanged) and never invoked
 * speculatively before the transition is attempted.
 *
 * ## [leaseId] identifies this specific transition attempt
 *
 * [entry] alone is not a unique key for one transition: the same
 * [io.dataloom.api.identifier.QueueEntryId] passes through this observer once
 * per acquisition cycle across its lifetime (for example: leased, rescheduled,
 * leased again, completed). [leaseId] is the exact
 * [io.dataloom.api.queue.QueueLease.id] assigned by the acquisition that
 * produced [entry] -- already guaranteed fresh per acquisition operation by
 * [io.dataloom.api.queue.QueueLeaseId]'s own class doc -- so `(entry.id,
 * leaseId)` is a stable, unique key for this one witnessed transition.
 *
 * ## Failure isolation is the implementation's responsibility
 *
 * [DurableQueueExecutionProcessor] does not wrap this call in a try/catch --
 * exactly the same posture it already takes for [QueueEntryExecutionHandler.execute].
 * An implementation that must never let an ordinary exception affect the real
 * queue-processing cycle (for example one that durably records an operational
 * event) is responsible for catching everything except
 * [kotlin.coroutines.cancellation.CancellationException] itself, the same
 * discipline [io.dataloom.runtime.execution.lifecycle.DispatchingSynchronizationLifecycleEventEmitter]'s
 * own `recordOperationalEvent` already applies.
 *
 * ## KMP compatibility
 *
 * Uses Kotlin standard-library and DataLoom API types only. Safe for use in
 * Kotlin Multiplatform common code.
 */
public fun interface QueueEntryTransitionObserver {

    /**
     * Called once for the already-acquired [entry] immediately after its
     * [outcome] has been durably persisted as a
     * [io.dataloom.api.queue.QueueProvider] transition under [leaseId].
     *
     * [entry] is the exact acquired entry, unchanged. [outcome] is the exact
     * outcome [DurableQueueExecutionProcessor] already computed and already
     * used to select which transition to persist.
     *
     * [kotlin.coroutines.cancellation.CancellationException] must not be
     * caught or suppressed.
     */
    public suspend fun onTransition(
        entry: QueueEntry,
        leaseId: QueueLeaseId,
        outcome: QueueEntryExecutionOutcome,
    )
}
