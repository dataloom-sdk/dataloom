package io.dataloom.api.operational

import io.dataloom.api.time.DataLoomInstant

/**
 * Bounded, payload-free summary of one [OperationalEventOutboxScope]'s
 * persisted [OperationalEventOutboxState] at one instant -- counts and one
 * timestamp only, never an envelope, attribute or identifier.
 *
 * @param pendingCount entries not acknowledged (the ones
 *   [DurableOperationalEventOutbox.entries] would return).
 * @param oldestPendingOccurredAt the smallest [OperationalEventEnvelope.occurredAt]
 *   among pending entries, or `null` when none are pending. This is the
 *   event's caller-supplied occurrence time, not the time it was appended, so
 *   an age derived from it inherits the same caveat
 *   [DurableOperationalEventOutbox]'s age-based retention documents.
 * @param acknowledgedRetainedCount acknowledged tombstones still retained
 *   (the entries [DurableOperationalEventOutbox.replay] could still reopen).
 */
public data class OperationalEventOutboxSummary(
    public val pendingCount: Int,
    public val oldestPendingOccurredAt: DataLoomInstant?,
    public val acknowledgedRetainedCount: Int,
) {
    init {
        require(pendingCount >= 0) { "pendingCount must not be negative." }
        require(acknowledgedRetainedCount >= 0) { "acknowledgedRetainedCount must not be negative." }
    }
}

/**
 * One observation an outbox made of a scope's persisted state -- the fact
 * that at [observedAt], the outbox either read or successfully wrote the
 * scope's record at [storeVersion] and it summarised as [summary].
 *
 * An observation is never authoritative about *now*: another process sharing
 * the same store may have changed the scope since. [storeVersion] lets a
 * consumer order observations of one scope (a higher version is a later
 * state of the same record); it is `null` when the scope had no record yet.
 */
public data class OperationalEventOutboxStateObservation(
    public val scope: OperationalEventOutboxScope,
    public val summary: OperationalEventOutboxSummary,
    public val storeVersion: Long?,
    public val observedAt: DataLoomInstant,
)

/**
 * Optional, non-suspending sink for [OperationalEventOutboxStateObservation]s,
 * passed to [DurableOperationalEventOutbox] as `stateObserver`.
 *
 * The outbox calls it after every state it has just loaded and evaluated or
 * successfully persisted (see the outbox's "Health observation"
 * documentation), which is the only way a *synchronous* caller can learn
 * anything about a durable store whose own reads suspend. Implementations
 * must return quickly, must not block, and must not call back into the
 * outbox. An exception thrown by an implementation is swallowed -- health
 * observation can never fail an append, acknowledgement, replay or read.
 */
public fun interface OperationalEventOutboxStateObserver {
    public fun onStateObserved(observation: OperationalEventOutboxStateObservation)
}
