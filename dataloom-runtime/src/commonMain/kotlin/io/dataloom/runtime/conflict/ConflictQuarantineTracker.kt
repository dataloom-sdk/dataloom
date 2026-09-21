package io.dataloom.runtime.conflict

import io.dataloom.api.conflict.ConflictQuarantineObservation
import io.dataloom.api.conflict.ConflictQuarantinePolicy
import io.dataloom.api.conflict.ConflictQuarantineScope
import io.dataloom.api.conflict.DurableConflictQuarantineLog
import io.dataloom.api.conflict.SynchronizationConflict
import io.dataloom.api.identifier.ConflictResolverId
import io.dataloom.api.time.DataLoomClock

/**
 * Opt-in loop/non-convergence guard for
 * [SynchronizationConflictOrchestrator]: counts each detected conflict against
 * its entity in a [DurableConflictQuarantineLog] under a
 * [ConflictQuarantinePolicy] and reports whether the entity is quarantined.
 *
 * Supplying one to the orchestrator is the only thing that turns quarantine
 * on; an orchestrator without one behaves exactly as it did before this type
 * existed.
 *
 * @param log the durable per-entity counter.
 * @param clock supplies the observation time recorded with each occurrence.
 * @param policy the threshold and optional window.
 */
public class ConflictQuarantineTracker(
    private val log: DurableConflictQuarantineLog,
    private val clock: DataLoomClock,
    private val policy: ConflictQuarantinePolicy = ConflictQuarantinePolicy(),
) {
    /**
     * Counts one occurrence of [conflict], recording [selectedResolverId] (the
     * resolver the orchestrator selected for it, or `null`), and returns the
     * durable observation. Performs no resolver lookup or invocation.
     */
    public suspend fun observe(
        conflict: SynchronizationConflict,
        selectedResolverId: ConflictResolverId?,
    ): ConflictQuarantineObservation =
        log.recordOccurrence(
            scope = ConflictQuarantineScope.of(conflict.entity),
            conflictId = conflict.id,
            resolverId = selectedResolverId,
            observedAt = clock.now(),
            policy = policy,
        )

    override fun toString(): String = "ConflictQuarantineTracker(policy=$policy)"
}
